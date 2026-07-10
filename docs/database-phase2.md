# Phase 2 PostgreSQL 与 Durable Queue

状态：已实现，本地 Gate 验证通过；Docker/Testcontainers 由 GitHub Actions 终验

本页记录 Phase 2 已落地的 Flyway 基线和 `queue_v1` 数据库边界。逻辑模型仍以
[数据模型](./data-model.md)、[状态机](./state-machine.md)和
[ADR-0001](./adr/0001-postgres-job-queue.md)为规范来源。

## 迁移

| 迁移 | 内容 |
|---|---|
| `V1__identity_and_securities.sql` | `citext`/`pgcrypto`、用户、外部 IdP subject、证券基础表、版本守卫 |
| `V2__durable_research_workflow.sql` | Research、Step、Attempt、API 幂等记录、审计、outbox、约束和队列索引 |
| `V3__durable_queue_api.sql` | 状态守卫及版本化 claim/lease/fencing/retry/cancel/reaper 函数 |
| `V4__research_job_projection_constraints.sql` | `current_step` 枚举及 status/currentStep/progress 投影一致性硬约束 |

迁移不创建演示用户、不保存默认密码，也不创建运行时数据库角色。用户由应用首次认证请求按
稳定 UUID provision；部署系统负责创建 Worker role 并显式授予最小权限。

## `queue_v1` 函数

| 函数 | 作用 |
|---|---|
| `claim_step(varchar, varchar[], integer)` | 用 `FOR UPDATE SKIP LOCKED` 领取一个能力匹配的到期 Step，数据库生成 attempt ID 与 fencing token |
| `heartbeat(uuid, uuid, integer)` | 仅为尚未过期的当前 token 续租，并返回取消 flag |
| `checkpoint_step(uuid, uuid, jsonb)` | 仅允许当前有效 token 保存小型版本化 checkpoint |
| `complete_step(uuid, uuid, varchar, jsonb)` | 原子提交 SHA-256 output manifest；相同 attempt/hash 可幂等重放，不同 hash 返回冲突 |
| `fail_step(uuid, uuid, boolean, varchar, varchar, integer, integer)` | 分类失败，按数据库时间计算指数退避和随机 jitter，或耗尽预算后失败 |
| `request_cancel(uuid, uuid, varchar, varchar)` | 校验 owner/admin，持久化取消 flag，取消所有 PENDING Step，并写审计/outbox |
| `cancel_step(uuid, uuid, jsonb)` | 当前 Worker 在取消 flag 已提交后终结 RUNNING attempt/step |
| `reap_expired(integer, integer, integer)` | 小批量 `SKIP LOCKED` 回收过期 lease，取消、重排或失败对应 Step |

所有 Worker 写路径都比较 `attempt_id + lease_token + RUNNING + lease_expires_at`，时间来自
PostgreSQL `statement_timestamp()`。token 不出现在 outbox payload。`complete_step` 只发布
`STEP_SUCCEEDED` outbox；后继 Step 的输入 hash 依赖上游输出，因此仍由 Java Orchestrator
消费事件后在独立业务事务中解锁，数据库函数不会猜测 DAG 输入。

函数均为 `SECURITY DEFINER`、固定 `search_path`（可信 schema 在前，`pg_temp` 显式置于最后，
避免调用者临时表遮蔽），且已撤销 `PUBLIC` 的 schema 和函数权限。
部署时为 Worker role 选择性授权，例如：

```sql
GRANT USAGE ON SCHEMA queue_v1 TO analytics_worker;
GRANT EXECUTE ON FUNCTION queue_v1.claim_step(varchar, varchar[], integer)
    TO analytics_worker;
GRANT EXECUTE ON FUNCTION queue_v1.heartbeat(uuid, uuid, integer)
    TO analytics_worker;
GRANT EXECUTE ON FUNCTION queue_v1.checkpoint_step(uuid, uuid, jsonb)
    TO analytics_worker;
GRANT EXECUTE ON FUNCTION queue_v1.complete_step(uuid, uuid, varchar, jsonb)
    TO analytics_worker;
GRANT EXECUTE ON FUNCTION queue_v1.fail_step(
    uuid, uuid, boolean, varchar, varchar, integer, integer
) TO analytics_worker;
GRANT EXECUTE ON FUNCTION queue_v1.cancel_step(uuid, uuid, jsonb)
    TO analytics_worker;
```

`request_cancel` 属于 Java 应用边界，`reap_expired` 属于受控调度器边界，不授予普通
Analytics Worker。

## 数据库不变量

- Research 公开状态只接受 canonical 17 状态；`current_step` 只接受 11 个 durable Step，
  且 CREATED、QUEUED/手动重试、活动阶段和终态的 status/currentStep/progress 组合由数据库
  约束保持一致。普通状态不能倒退，`COMPLETED` 和
  `CANCELLED` 绝对不可逆。
- 同 Research 手动 retry 是唯一显式例外：`FAILED | PARTIALLY_COMPLETED -> QUEUED`，同时
  清空完成、启动和取消字段；已成功且输入/实现版本未变化的 Step 不得更新。
- `row_version` 每次更新必须恰好加一；数据库统一刷新 `updated_at`。
- 一个 Step 最多一个 `RUNNING` Attempt；attempt number 和 lease token 在 Step 内唯一。
- `RUNNING` Attempt 必须具有 Worker、token、heartbeat 和 lease；终结 attempt 必须具有
  `completed_at`，成功 attempt/step 必须具有合法小写 SHA-256 output hash。
- `available_at=NULL` 表示依赖尚未满足；只有到期 `PENDING` Step 可被领取。
- Research 软删除不可撤销；常用查询和队列索引排除软删除记录。
- API 幂等范围唯一键是
  `(user_id, http_method, request_path, idempotency_key)`；处理中记录没有响应，完成记录必须有
  HTTP 状态与 JSON 响应。

## 实际 PostgreSQL 验证

迁移已在 PostgreSQL 17 全新数据库按 V1 → V2 → V3 → V4 实际执行，并再次执行
Flyway validate/migrate，确认 checksum 稳定且第二次迁移数为零。数据库探针覆盖：

- 20 个并发消费者只创建一个 RUNNING attempt；
- heartbeat、短 heartbeat 不缩短长 lease、checkpoint、成功提交及相同 hash 重放；
- retryable failure、退避重排、预算耗尽；
- 取消 PENDING Step、RUNNING Worker 协作退出；
- lease 到期回收，以及旧 token 对 heartbeat/checkpoint/complete/fail/cancel 均返回
  `STALE_LEASE`；
- 后段人工重试只允许从其已保存的 QUEUED checkpoint 恢复，任意跨阶段投影被拒绝；
- 并发软删除在 owner-scoped 行锁后串行化，重复请求保持幂等；
- 调用者同名临时表不能遮蔽 definer 写路径，状态转换仍产生同事务 outbox；
- 非法 currentStep 或 status/currentStep/progress 组合由命名约束以 SQLSTATE `23514` 拒绝。
