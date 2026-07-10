# 项目进度

最后更新：2026-07-10

| Phase | 状态 | 结果 |
| --- | --- | --- |
| Phase 0：需求与架构 | 完成 | 需求、ADR、架构/数据流/状态机/ER、OpenAPI、Analytics/LLM Schema、方法论、安全、风险和计划已固化 |
| Phase 1：项目骨架 | 完成 | Web、API、Analytics、Compose、Health 和 CI 已完成；远端 Docker build、五服务启动与 smoke 通过，Gate G1 通过 |
| Phase 2：持久任务与状态机 | 完成 | Flyway V1–V4、Research API、durable queue、lease/fencing/reaper、状态机、幂等、重试/取消/软删除、所有权、审计/outbox 与健康降级已实现；Gate G2 通过 |
| Phase 3：Mock 纵向闭环 | 未开始 | Gate G2 已通过，可以开始 |
| Phase 4-9 | 未开始 | 按 `implementation-plan.md` 推进 |

## 已验证

- 原始 DOCX 需求已全文读取并完成结构/页面核对；
- OpenAPI 3.1 YAML 可解析，本地引用与必需操作完整；
- LLM/Analytics JSON Schema 是合法 JSON；
- Web：ESLint、TypeScript、8 个 Vitest 测试和 Next.js production build 通过；Phase 1 的 3 个 Playwright E2E 保持通过；
- API：Java 21 / Spring Boot 3.5，89 个 Surefire 测试与 package 通过；
- PostgreSQL 17：Flyway V1–V4、重复 validate/migrate、Hibernate validate、真实 HTTP 生命周期、幂等、取消、软删除与查询筛选通过；
- Analytics：Ruff、strict mypy、7 个 pytest 测试通过，覆盖率 97%；
- GitHub Actions：Gate G2 的 Web、API（含 27 个 Failsafe/Testcontainers 测试）、Analytics、secret scan、Docker Compose build/up/smoke/down 全部通过（run `29076405369`）；
- 文档相对链接、YAML/Shell 语法和 Git whitespace 检查通过。

## 当前限制

- 本机没有 Docker；Phase 2 容器/Testcontainers 验证已由 GitHub Actions Linux runner 完成。
- 真实行情和基本面 Provider 尚未选择，这是 Phase 7 前的有意许可决策门。
