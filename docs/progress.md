# 项目进度

最后更新：2026-07-10

| Phase | 状态 | 结果 |
| --- | --- | --- |
| Phase 0：需求与架构 | 完成 | 需求、ADR、架构/数据流/状态机/ER、OpenAPI、Analytics/LLM Schema、方法论、安全、风险和计划已固化 |
| Phase 1：项目骨架 | 完成 | Web、API、Analytics、Compose、Health 和 CI 已完成；远端 Docker build、五服务启动与 smoke 通过，Gate G1 通过 |
| Phase 2：持久任务与状态机 | 完成 | Flyway V1–V4、Research API、durable queue、lease/fencing/reaper、状态机、幂等、重试/取消/软删除、所有权、审计/outbox 与健康降级已实现；Gate G2 通过 |
| Phase 3：Mock 纵向闭环 | 实现完成，G3 远程终验待通过 | MU/NVDA/RKLB 固定 Mock Provider、SPY/QQQ 基准、确定性量化、Evidence/Claim、报告验证/原子发布、历史、情景和三种导出已实现；本地闭环已通过 |
| Phase 4-9 | 未开始 | 按 `implementation-plan.md` 推进 |

## 已验证

- 原始 DOCX 需求已全文读取并完成结构/页面核对；
- OpenAPI 3.1 YAML 可解析，本地引用与必需操作完整；
- LLM/Analytics JSON Schema 是合法 JSON；
- Web：ESLint、TypeScript、20 个 Vitest、Next.js production build 与 4 个 Playwright 用例通过；闭环 E2E 覆盖创建 → 进度 → 报告/Evidence → Bull/Base/Bear → 三种导出 → 历史重开；
- API：Java 21 / Spring Boot 3.5，139 个 Surefire 测试通过；Mock Provider、Analytics HTTP 错误分类、Worker heartbeat/自隔离、Evidence/Claim、报告验证、查询 API 和确定性导出均有自动化覆盖；
- PostgreSQL 17：Flyway V1–V6、报告/Evidence/Claim 约束、`MIXED_TEST` 发布与导出阻断、不可变历史与原子步骤推进已本地验证；
- Analytics：Ruff、strict mypy、23 个 pytest 通过，覆盖率 91.29%；
- 本地真实服务链路：MU、NVDA、RKLB 完整研究均形成已验证报告；关闭基本面叙事时安全部分完成，关闭宏观时完整完成；所有保留的重要 Claim 都关联同任务 Evidence；
- 故障恢复：Analytics 不可用时任务在重试预算耗尽后失败，恢复服务后从 `RUN_QUANT_ANALYSIS` 续跑，早期成功步骤未重复执行，最终发布报告与 manifest；
- 导出：Markdown/HTML/PDF 响应类型、文件名、ETag、SHA-256、版本与数据模式已验证；重复 PDF 字节一致，中文 PDF 已完成逐页视觉检查和文本抽取检查，HTML 无脚本和外部资源；
- GitHub Actions：Gate G2 的 Web、API（含 27 个 Failsafe/Testcontainers 测试）、Analytics、secret scan、Docker Compose build/up/smoke/down 全部通过（run `29076405369`）；
- Phase 3 详细本地证据和 Gate 待办见 [`phase3-test-matrix.md`](./phase3-test-matrix.md)。

## 当前限制

- 本机没有 Docker；Gate G3 的容器/Testcontainers/Compose 终验必须由 GitHub Actions Linux runner 完成，当前状态是“待通过”，不是“已通过”。
- 普通用户闭环只允许 `dataMode=MOCK`；目标只支持 MU/NVDA/RKLB，基准只支持 SPY/QQQ，深度固定 `STANDARD`，周期固定 `5y`，技术分析必须启用。
- 基本面开关只控制可选叙事分析步骤；情景计算所需的规范化基本面数据仍会获取。宏观开关关闭时跳过宏观取数。
- 真实行情和基本面 Provider 尚未选择，这是 Phase 7 前的有意许可决策门。
- 真实 OpenAI 调用尚未接入；Phase 3 只发布确定性 Mock 报告。
