# ADR-0001：使用 PostgreSQL Durable Lease Queue

- 状态：已接受
- 日期：2026-07-09
- 决策范围：Phase 0 至首个生产规模阶段
- 相关文档：[架构基线](../architecture.md)、[数据流](../data-flow.md)、[状态机](../state-machine.md)

## 1. 背景

Research 包含证券解析、数据采集、质量校验、量化分析、Evidence 构建和报告验证，单步可能运行数秒到数十分钟。系统必须处理：

- Research 创建与入队之间的崩溃；
- 多个 Python Worker 并发领取；
- Worker 网络分区、进程暂停或结果提交前崩溃；
- 至少一次执行导致的重复计算；
- 用户取消与 claim/完成/报告发布竞争；
- Redis 重启或完全不可用；
- 非关键模块失败但仍能形成安全报告。

业务真相已经位于 PostgreSQL。首期负载重正确性、可审计性和低运维复杂度，不需要极端消息吞吐。

## 2. 决策

使用 PostgreSQL 的 `research_steps + step_attempts` 作为持久任务队列，采用：

- `FOR UPDATE SKIP LOCKED` 并发 claim；
- 短期 lease 与 heartbeat；
- 每 attempt 随机 fencing token；
- 数据库时间计算到期与退避；
- transactional outbox；
- Reaper 回收过期 attempt；
- 条件更新和输出 hash 实现幂等发布。

不建立与领域步骤平行的第二张 `job_queue` 表。`research_steps` 是逻辑步骤也是队列项，`step_attempts` 保存每次实际执行与 lease 历史。

PostgreSQL 是权威队列；Redis 只用于缓存、限流、SSE 扇出和取消提示。执行语义为 at-least-once，通过 fencing 和幂等提交实现 effectively-once effect。

## 3. 选择理由

1. **事务一致性**：Research、runnable step 和 outbox 可在同一事务提交，无数据库/Broker 双写窗口。
2. **崩溃恢复**：步骤、attempt、lease、错误、取消和输出 hash 均可查询、备份与审计。
3. **并发能力**：`SKIP LOCKED` 让 Worker 领取不同步骤，不产生全局串行等待。
4. **运维收敛**：首期无需额外维护 Broker、权限、备份和本地开发环境。
5. **可逆性**：outbox 和 queue API 保留传输边界，达到规模阈值后可接入专用 Broker，而不改变状态模型。

## 4. 状态映射

Research 公开状态严格采用 canonical 枚举；队列实现不能新增公开 `RUNNING_JOB`、`RETRY_WAIT` 等状态。

内部状态：

| 层级 | 持久状态 | 说明 |
| --- | --- | --- |
| `research_steps` | `PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED, CANCELLED` | `PENDING + available_at>now()` 表示等待重试 |
| `step_attempts` | `RUNNING, SUCCEEDED, FAILED, CANCELLED, LEASE_EXPIRED` | `FAILED.retryable` 决定 Step 是否重新排队 |
| Research | canonical 阶段与四个终态 | `cancellation_requested` 是正交 flag |

队列不产生 `PARTIALLY_COMPLETED`。只有 Java finalizer 能根据安全报告与 completion policy 把 Research 置为 `COMPLETED`、`PARTIALLY_COMPLETED` 或 `FAILED`；用户取消先提交时最终为 `CANCELLED`。

## 5. 表结构增量

以下 SQL 是对 `docs/data-model.md` 中规范表的队列字段增量，实际 Flyway migration 应与建表顺序合并。payload 只保存版本化引用和参数，不保存大体积行情原文。

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE research_steps
    ADD COLUMN payload_version integer NOT NULL DEFAULT 1,
    ADD COLUMN payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN implementation_version varchar(128) NOT NULL,
    ADD COLUMN priority integer NOT NULL DEFAULT 0,
    ADD COLUMN available_at timestamptz,
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN max_attempts integer NOT NULL DEFAULT 3,
    ADD CONSTRAINT ck_research_steps_attempt_budget
        CHECK (attempt_count >= 0
               AND max_attempts > 0
               AND attempt_count <= max_attempts),
    ADD CONSTRAINT ck_research_steps_runnable_time
        CHECK (status = 'PENDING' OR available_at IS NULL);

ALTER TABLE step_attempts
    ADD COLUMN lease_token uuid,
    ADD COLUMN heartbeat_at timestamptz,
    ADD CONSTRAINT ck_step_attempt_running_lease
        CHECK (
            (status = 'RUNNING'
             AND worker_id IS NOT NULL
             AND lease_token IS NOT NULL
             AND lease_expires_at IS NOT NULL
             AND heartbeat_at IS NOT NULL
             AND completed_at IS NULL)
            OR
            (status <> 'RUNNING'
             AND completed_at IS NOT NULL)
        );

CREATE UNIQUE INDEX ux_step_attempt_one_running
    ON step_attempts (research_step_id)
    WHERE status = 'RUNNING';

CREATE UNIQUE INDEX ux_step_attempt_token
    ON step_attempts (research_step_id, lease_token)
    WHERE lease_token IS NOT NULL;

CREATE INDEX ix_research_steps_claim
    ON research_steps (priority DESC, available_at, id)
    WHERE status = 'PENDING' AND available_at IS NOT NULL;

CREATE INDEX ix_step_attempt_reaper
    ON step_attempts (lease_expires_at, id)
    WHERE status = 'RUNNING';
```

必要不变量继续由规范模型提供：

- `UNIQUE(research_job_id, step_type)`；
- `UNIQUE(research_step_id, attempt_number)`；
- Step 成功时保存 `successful_output_hash`；
- Research 终态、报告版本和取消 flag 约束。

`available_at=NULL` 表示依赖尚未满足；Java 解锁步骤时设置数据库当前时间。重试时 Step 回到 `PENDING` 并设置未来 `available_at`。非 `PENDING` 状态必须清空该字段。

## 6. 原子 claim

Worker 调用 `queue_v1.claim_step(worker_id, supported_step_types, lease_seconds)`。数据库函数在一个短事务中执行以下核心 SQL，并追加 `STEP_STARTED` outbox；示例省略 outbox CTE 以避免把其列结构固化在 ADR 中。

```sql
WITH candidate AS MATERIALIZED (
    SELECT s.id
    FROM research_steps AS s
    JOIN research_jobs AS r
      ON r.id = s.research_job_id
    WHERE s.status = 'PENDING'
      AND s.available_at IS NOT NULL
      AND s.available_at <= statement_timestamp()
      AND s.attempt_count < s.max_attempts
      AND s.step_type = ANY (:supported_step_types)
      AND r.cancellation_requested = false
      AND r.status NOT IN (
          'COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED'
      )
    ORDER BY s.priority DESC, s.available_at, s.id
    FOR UPDATE OF s SKIP LOCKED
    LIMIT 1
),
claim_context AS MATERIALIZED (
    SELECT id AS step_id,
           gen_random_uuid() AS attempt_id,
           gen_random_uuid() AS lease_token
    FROM candidate
),
updated_step AS MATERIALIZED (
    UPDATE research_steps AS s
    SET status        = 'RUNNING',
        attempt_count = s.attempt_count + 1,
        available_at  = NULL,
        updated_at    = statement_timestamp(),
        row_version   = s.row_version + 1
    FROM claim_context AS c
    WHERE s.id = c.step_id
    RETURNING s.*
),
inserted_attempt AS (
    INSERT INTO step_attempts (
        id, research_step_id, attempt_number, status,
        retryable, input_hash, worker_id, lease_token,
        lease_expires_at, heartbeat_at, started_at, created_at
    )
    SELECT c.attempt_id, s.id, s.attempt_count, 'RUNNING',
           false, s.input_hash, :worker_id, c.lease_token,
           statement_timestamp() + make_interval(secs => :lease_seconds),
           statement_timestamp(), statement_timestamp(), statement_timestamp()
    FROM updated_step AS s
    JOIN claim_context AS c ON c.step_id = s.id
    RETURNING id, research_step_id, lease_token, lease_expires_at
)
SELECT s.*, a.id AS attempt_id, a.lease_token, a.lease_expires_at
FROM updated_step AS s
JOIN inserted_attempt AS a ON a.research_step_id = s.id;
```

要求：

- 无任务时返回空结果，不是错误；
- Worker 不选择 token，也不在计算期间持有数据库事务/行锁；
- 初期一次 claim 一个步骤；只有压测证明必要时才小批量领取；
- `supported_step_types` 实现 Worker 能力路由，不能仅领取后再拒绝；
- claim 与 attempt/outbox 任一步失败时整体回滚。

## 7. Heartbeat 与 fencing

heartbeat 使用数据库时间、当前 attempt ID 和 token：

```sql
UPDATE step_attempts AS a
SET heartbeat_at     = statement_timestamp(),
    lease_expires_at = statement_timestamp()
                       + make_interval(secs => :lease_seconds)
FROM research_steps AS s,
     research_jobs AS r
WHERE a.id = :attempt_id
  AND a.research_step_id = s.id
  AND s.research_job_id = r.id
  AND a.status = 'RUNNING'
  AND a.lease_token = :lease_token
  AND a.lease_expires_at > statement_timestamp()
RETURNING a.lease_expires_at, r.cancellation_requested;
```

影响 0 行即 `STALE_LEASE`。已过期 lease 不得复活，即使 Reaper 尚未运行。

默认参数：lease 60 秒、heartbeat 20 秒；按 step type 配置并通过故障注入校准。Worker 连续两次 heartbeat 失败或收到 `STALE_LEASE` 后必须 self-fence：停止发布、停止新外部调用并只清理自己的暂存对象。

token 是短期 fencing credential。checkpoint、完成、失败和取消全部匹配 `attempt_id + lease_token + RUNNING`；只匹配 Worker ID 或 attempt number 不足以授权。

## 8. 完成与幂等发布

数据库函数 `queue_v1.complete_step(...)` 在一个事务内：

1. 锁定 attempt、Step 和 Research；
2. 验证 token、`RUNNING`、lease 未过期、Research 未终态且未取消；
3. 验证 payload/input/implementation version 与 output manifest schema；
4. 按步骤逻辑幂等键保存或复用 output hash；
5. 相同键同 hash 返回既有结果；相同键不同 hash 返回 `IDEMPOTENCY_CONFLICT` 并告警；
6. attempt/Step → `SUCCEEDED`，写完成时间和 `successful_output_hash`；
7. 写 `STEP_SUCCEEDED` outbox 后提交。

Worker 先写暂存输出、再提交小型 manifest/hash。只有事务成功后输出才可被下游引用；未引用暂存对象按 TTL 清理。报告、Claim 和 Research 终态仍由 Java 验证/发布。

## 9. 失败、重试与 Reaper

主动失败由 `queue_v1.fail_step(...)` 分类：

- 可重试且有预算：attempt → `FAILED(retryable=true)`；Step → `PENDING`；
- 永久错误或预算耗尽：attempt → `FAILED`；Step → `FAILED`；
- Research 已取消：attempt/Step → `CANCELLED`。

退避由数据库时间计算：

```text
delay = min(base_delay * 2^(attempt_count - 1), max_delay) + random_jitter
available_at = database_now + delay
```

Reaper 使用小批量 `FOR UPDATE SKIP LOCKED` 扫描：

```text
step_attempts.status = RUNNING
AND lease_expires_at <= database_now
```

锁定后再次比较扫描时观察到的 token：

- Research 已取消：attempt/Step → `CANCELLED`；
- 未取消且有预算：attempt → `LEASE_EXPIRED`，Step → 带退避 `PENDING`；
- 未取消且预算耗尽：attempt → `LEASE_EXPIRED`，Step → `FAILED`。

每种转换都写 outbox。heartbeat 和 Reaper 竞争时由行锁与条件谓词决定唯一结果；旧 Worker 的晚到提交被 token 拒绝。

## 10. 取消

Java 取消事务：

1. 锁定 `research_jobs`；若已为 `COMPLETED | PARTIALLY_COMPLETED | FAILED | CANCELLED`，幂等返回既有终态；
2. 写 `cancellation_requested=true`、时间、审计和 outbox，不引入新的公开状态；
3. 将 `PENDING` Step 直接置 `CANCELLED`；
4. 保留运行中 lease，由 Worker 协作退出；
5. 提交后才发布 Redis 提示。

步骤成功提交必须检查 Research 未取消。取消 flag 先提交时，晚到成功失败；Reaper 也不得重新排队。全部活动 attempt 终结后，Java 把 Research 置 `CANCELLED`。

如果 `COMPLETED` 或 `PARTIALLY_COMPLETED` 报告事务先提交，取消是 no-op/race result。用户主动取消先提交后，即使已有内部 artifact，也不能改判 `PARTIALLY_COMPLETED`。

## 11. 部分完成

队列层没有部分成功 Step：每个 Step 明确为 `SUCCEEDED | FAILED | SKIPPED | CANCELLED`。Java finalizer 只有在未取消时才评估：

- 完整策略通过且报告验证通过 → `COMPLETED`；
- 核心行情、核心量化和安全报告可用，但可降级模块缺失或不合格 Claim 被移除 → `PARTIALLY_COMPLETED`；
- 无法形成安全报告 → `FAILED`。

`PARTIALLY_COMPLETED` 的报告必须列出缺失步骤、数据质量、limitations 和验证问题；不能把 checkpoint 或未验证半成品计入最低交付。

## 12. Redis 与唤醒

可选使用 Redis 或 PostgreSQL `LISTEN/NOTIFY` 降低空闲延迟，但仅作 wake-up hint：

- 通知可丢失、重复和乱序；
- 收到通知后仍执行正常 claim SQL；
- Worker 保留带抖动的 PostgreSQL 轮询；
- payload 和唯一任务副本不得只存在 Redis；
- Redis 锁不得替代 lease token。

## 13. 权限与安全

- Python 使用独立数据库角色，只授予 `queue_v1.claim_step`、`heartbeat`、`checkpoint`、`complete_step`、`fail_step`、`cancel_step` 的 `EXECUTE`。
- Worker 不获得用户、Research 终态、Claim、Evidence 或报告表的直接更新权限。
- payload 不含 Provider 密钥；凭据由 secret manager/部署环境注入。
- lease token 不写普通日志、不进入前端、不作为长期身份。
- 错误只保存结构化 code 和安全消息，Provider 原文按数据保留策略处理。

## 14. 运维基线

| 参数 | 初始建议 | 调整依据 |
| --- | --- | --- |
| 空队列轮询 | 250ms 起，退避至 2s，含抖动 | 空闲 QPS 与启动延迟 |
| lease | 60s | 最长调度暂停、网络抖动、heartbeat SLO |
| heartbeat | 20s | 小于 lease 的三分之一 |
| Reaper batch | 100 | 锁持有时间和过期积压 |
| 默认 max attempts | 3 | Provider 特征、成本与用户 SLO |

必须监控：可领取 Step 数、最老 `available_at`、claim p95/p99、锁等待、heartbeat 失败、`STALE_LEASE`、lease 到期率、attempt 分布、Reaper 积压、取消延迟、outbox 堆积、幂等冲突和部分完成率。

高更新表需要独立 autovacuum 监控和归档策略。不要在没有真实数据前提前分区。

## 15. 后果

### 正面

- Research 创建与入队原子一致；
- Redis 故障不丢任务；
- Step/attempt/lease 与领域审计统一；
- 本地开发和故障排查简单；
- Java/Python 共享显式版本化协议。

### 负面与缓解

- heartbeat 增加 PostgreSQL 写负载：通过合理 lease、部分索引、连接池和指标调优。
- 错误查询会导致扫描/锁竞争：所有 claim/Reaper SQL 必须在生产量级做 `EXPLAIN (ANALYZE, BUFFERS)` 和并发压测。
- Python 获得有限数据库访问：通过独立 role、schema 与 SECURITY DEFINER 函数最小化权限，并固定 `search_path`。
- PostgreSQL Queue 不适合无限事件流和超大 fan-out：保留 outbox/Broker 适配边界。

## 16. 未选择的方案

### Redis List/Streams 或 Redis-backed Celery

低延迟且生态成熟，但作为唯一队列会让任务真相依赖 Redis 持久化，并产生业务事务与入队双写。Redis 仍保留为非权威加速层。

### RabbitMQ、SQS 或同类 Broker

具备成熟 ack、visibility timeout 和死信能力，但首期增加基础设施，仍需 outbox 解决双写。未来可由 outbox relay 接入。

### Kafka

适合高吞吐事件日志和回放，不天然等于支持优先级、延迟执行和 lease 的工作队列，当前收益不足。

### Java 内存队列或 Spring `@Async`

进程崩溃会丢状态，多实例协调和审计不足，明确拒绝作为可靠队列。

## 17. 重新评估条件

完成索引、查询、连接池和数据库资源调优后，仍出现以下情况时评估专用 Broker：

- claim p95 持续超过 100ms 并违反最老任务等待 SLO；
- heartbeat/队列写入持续挤压业务事务或复制延迟；
- 数百个以上持续活跃 Worker 使连接数与更新率成为主要瓶颈；
- 需要跨区域队列、超大 fan-out、Broker 级流控或长期事件回放；
- queue 数据量和保留需求无法在目标成本内 vacuum/归档。

迁移时 PostgreSQL 仍保存权威 Step/Attempt 状态；outbox 向 Broker 发布命令，Broker ack 只驱动条件转换。幂等键、lease/fencing、取消与部分完成语义保持不变。

## 18. 验证清单

- 真实 PostgreSQL 下至少 20 个并发消费者，一个 Step 只有一个 RUNNING attempt。
- 在 claim 后、heartbeat 中、输出暂存后和提交前注入崩溃。
- 网络分区后旧 Worker 恢复，所有旧 token 写入均被拒绝。
- heartbeat 在到期前可续租、到期后不能复活。
- 取消与 claim、heartbeat、完成、Reaper、报告终态的所有竞争顺序符合规范。
- API、outbox 和步骤提交重放保持幂等。
- Redis 停机时任务仍可创建、领取、续租、完成、取消和查询。
- 非关键模块失败可进入 `PARTIALLY_COMPLETED`；取消先提交时始终为 `CANCELLED`。
