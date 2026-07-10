# Phase 2 Gate 测试矩阵

状态：本地验证通过；等待 GitHub Actions Docker/Testcontainers 终验

日期：2026-07-10

## 1. 测试执行边界

| 测试层 | 命名 | Maven 生命周期 | Docker | 用途 |
| --- | --- | --- | --- | --- |
| 单元/切片测试 | `*Test`、`*Tests` | Surefire，`mvn test` | 不需要 | 领域状态机、校验、映射、Web/Security 切片 |
| 基础设施集成测试 | `*IT` | Failsafe，`mvn verify` | 需要 | PostgreSQL、Flyway、Redis、JPA、事务和锁 |
| Compose smoke | Shell/HTTP | GitHub Actions | 需要 | 镜像、五服务启动和公开健康端点 |

本机没有 Docker 时运行：

```bash
./mvnw test
./mvnw verify -DskipITs
```

GitHub Actions 或有 Docker 的环境运行：

```bash
./mvnw verify
```

`PostgresRedisIntegrationTestSupport` 统一注册动态 JDBC/Redis 地址。测试不得假设固定端口，也不得连接开发数据库。PostgreSQL 和 Redis 镜像版本必须与 `docker-compose.yml` 保持一致。

## 2. Gate G2 必测矩阵

| 能力 | 核心场景 | 建议层级 | 关键断言 |
| --- | --- | --- | --- |
| 状态机 | 每条合法转换 | 单元 | 新状态、时间戳、progress/currentStep 一致 |
| 状态机 | 非法转换与终态再转换 | 单元 | 明确错误码；无任何持久化副作用 |
| 创建幂等 | 相同用户、端点、Idempotency-Key、相同请求 | PostgreSQL IT + API IT | 仅一个 Research；返回相同资源 ID |
| 幂等冲突 | 相同 Key、不同请求哈希 | PostgreSQL IT + API IT | `409`；原资源不变 |
| 队列领取 | 两个 Worker 同时 claim | PostgreSQL 并发 IT | 每个 Step 只被一个 Worker 持有；使用数据库锁而非 JVM 锁 |
| lease | heartbeat 延长有效 lease | PostgreSQL IT | `leaseExpiresAt` 单调前移；owner 不变 |
| lease 回收 | lease 到期后由另一 Worker 领取 | PostgreSQL IT | attempt 增加；旧 Worker 无法完成新 lease |
| fencing | 过期 Worker 晚到提交 | PostgreSQL 并发 IT | 更新行数为 0 或冲突；不得覆盖新 Worker 结果 |
| definer 边界 | 调用者创建同名临时 outbox 表后 claim | PostgreSQL IT | 事件只写入 `public.outbox_events`；临时表保持为空 |
| 重启恢复 | claim 后模拟进程退出并等待 lease 到期 | PostgreSQL IT | 新 Worker 可继续；Research 不丢失 |
| 步骤幂等 | 已成功的 inputHash + implementationVersion 再消费 | PostgreSQL IT | 不新增成功结果；复用原输出 |
| 输入变更 | inputHash 或 implementationVersion 变化 | PostgreSQL IT | 产生新的执行尝试，不错误复用旧输出 |
| 自动重试 | 可重试错误且未超限 | 单元 + PostgreSQL IT | backoff、attempt、nextRunAt 正确 |
| 失败终止 | 不可重试错误或达到上限 | 单元 + PostgreSQL IT | Step/Research 进入正确终态并保留错误摘要 |
| 取消 | QUEUED/RUNNING 时请求取消 | PostgreSQL IT + API IT | 标记 `cancellationRequested`；安全点结束为 `CANCELLED` |
| 取消竞态 | Worker 成功提交与取消/回收竞争 | PostgreSQL 并发 IT | 取消先提交时成功被拒绝并收敛为 CANCELLED；不存在双终态 |
| 用户重试 | 失败 Research 从后段 checkpoint 重试 | API IT | 只投影已保存的首个可运行 Step；成功 Step 不重复执行；任意跳跃被拒绝 |
| 软删除 | 删除终态 Research（含并发） | API IT | owner 行锁串行化；重复删除幂等；数据库仍保留审计数据 |
| 删除约束 | 删除运行中 Research | API IT | 统一业务错误；任务保持可查询 |
| 所有权 | 用户 A 查询/修改用户 B 资源 | Security API IT | 按安全契约返回 `404`/`403`，无 IDOR |
| Redis 降级 | Redis 连接真实不可达 | Spring IT | API 为 DEGRADED；PostgreSQL/queue 仍为 UP，核心状态不丢失 |
| 数据约束 | 重复 publicId、非法枚举、外键缺失 | PostgreSQL IT | 数据库约束拒绝写入，映射为统一错误 |
| Flyway | 空数据库启动 | PostgreSQL IT | 全部迁移成功；Hibernate `validate` 通过 |
| Flyway | 已迁移数据库重复 validate/migrate | PostgreSQL IT | 零新增迁移；checksum/history 无漂移 |

报告发布终态与取消的原子竞态依赖 Phase 3 的 `report_versions` 发布事务。本阶段 finalizer
对没有已发布验证报告的 `COMPLETED/PARTIALLY_COMPLETED` 失败关闭，因此该竞态随报告模型在
Gate G3 验证，不能用两个裸 boolean 在 Phase 2 制造伪成功终态。

## 3. API 契约覆盖

以下端点通过 MVC 契约测试与 `ResearchHttpLifecycleIT` 的真实
Basic Auth → Controller → transaction → PostgreSQL 链路组合覆盖成功、认证失败、所有权失败、
非法输入和非法状态：

- 创建、列表、详情和状态；
- 取消、重试和软删除；
- Idempotency-Key 缺失、重放与冲突；
- 不存在或格式错误的 Research public ID；
- 统一 Problem Details、错误码、Request ID 和 Research ID 日志上下文。

## 4. 并发测试要求

1. 使用独立事务和线程屏障制造真实竞争，不用顺序调用模拟并发。
2. 每个并发测试设置确定性超时，失败时输出 Worker、lease token 和 Research ID。
3. 至少循环执行关键 claim/fencing 场景，避免一次偶然通过。
4. 不断言线程执行顺序，只断言数据库不变量。
5. 测试结束后按 Research ID 清理，不使用全库 `truncate` 影响并行测试。

## 5. 已实现的 Gate 测试载体

- `ResearchHttpLifecycleIT`：真实 HTTP 创建时延、list/detail/status、幂等重放/冲突、IDOR、
  FAILED → retry、取消、重复软删除、审计与 outbox 保留；
- `ResearchLifecycleIT`：原子持久化、11 Step 计划、所有权、失败收敛、输入/实现版本重试复用；
- `DurableQueueIT`：20 消费者竞争三轮、lease、单调 heartbeat、reaper、完整 fencing、
  `SECURITY DEFINER` 临时表遮蔽防护、取消与退避；
- `ResearchJobConstraintsIT`：currentStep 枚举及 status/currentStep/progress 非法组合拒绝；
- `InfrastructureContainersIT`：PostgreSQL/Redis 可达及 Flyway 重复 validate/migrate；
- `RedisUnavailableHealthIT`：Redis 不可达但 durable queue 正常时的 DEGRADED；
- Surefire 单元/MVC 测试：全状态转换矩阵、领域不变量、账号状态、Problem Details 与健康契约。

## 6. Gate G2 通过条件

- Surefire 单测和 Failsafe 集成测试全部通过且无跳过的 `*IT`；
- GitHub Actions runner 确认 PostgreSQL/Redis 容器实际启动；
- 创建接口快速返回 `QUEUED`，不等待 Worker 完成；
- 重启、重复领取、过期 Worker 和用户重试均不重复提交成功结果；
- 状态、进度、当前步骤、耗时及安全错误均符合 OpenAPI；
- Compose smoke 继续通过，且不引入真实 API Key 依赖。
