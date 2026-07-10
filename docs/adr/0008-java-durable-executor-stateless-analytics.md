# ADR 0008: Java Durable Executor 与无状态 Python Analytics

- 状态：Accepted
- 日期：2026-07-10

## 背景

Phase 2 已由 PostgreSQL `research_steps + step_attempts + queue_v1` 建立持久
lease、heartbeat、fencing 与重试边界。早期架构图同时把 Python 描述为直接
claim 队列的 Worker，又把 Analytics 定义为不访问数据库、由 Java 传入完整
SourceSnapshot 输入的内部计算服务。这会产生两套编排者，并使 Provider 数据、
Evidence、报告发布和权限边界难以保持在一个 Java 事务模型内。

## 决策

Java API 进程内运行有界的 durable executor：

1. executor 只通过版本化 `queue_v1` 函数 claim、heartbeat、checkpoint、失败、
   取消和完成步骤；可靠性来自 PostgreSQL，不来自调度线程内存；
2. Java 的步骤 Handler 调用 Provider 端口、持久化 SourceSnapshot 和领域
   artifact，并调用 Python Analytics；
3. Python Analytics 是无状态的内部 HTTP 计算服务，只接收版本化输入，返回
   确定性结果，不访问 PostgreSQL、Redis、Provider 或 LLM；
4. Java 继续独占用户授权、Research 公共状态、Evidence/Claim 验证、报告发布
   和终态裁决；
5. `queue_v1.complete_step_and_advance` 在当前 lease fencing 下原子完成步骤并
   解锁下一个非 `SKIPPED` 步骤。最后一步只完成，不代替 Java 报告发布事务。

executor 与 API 可位于同一部署单元，但使用独立、有界线程池和独立数据库
权限。普通单元测试默认关闭 executor；专用集成与 Compose profile 显式开启。

## 结果

优点：

- 只有一套业务编排和 artifact 写入边界；
- Python 保持易测试、可水平扩展的纯计算服务；
- 不增加第六个 MVP Compose 服务；
- Java 可在同一事务验证 lease、写入报告和裁决 Research。

代价：

- Java API 部署同时承载后台执行负载，必须使用有界并发和独立连接预算；
- 未来若把 executor 拆成独立部署，需要保持相同 `queue_v1` 与 Handler 契约；
- 长 Analytics 调用仍需 heartbeat、超时和协作式取消。

## 被否决方案

- Python 直接 claim 全部步骤并写业务表：会扩大数据库权限，且与 Java 对
  Evidence、报告和终态的独占所有权冲突。
- 内存队列或 `@Async`：进程重启会丢失任务，不满足 Gate G2/G3。
- 新增消息中间件：Phase 3 不需要第二套权威队列，增加运维和一致性成本。
