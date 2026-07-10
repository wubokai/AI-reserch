# 设计与交付文档索引

本目录同时保存实现前的契约/决策基线和各阶段的验证记录。代码若改变这些约定，必须在同一变更中更新对应文档、Schema 和测试。

| 文档 | 作用 |
| --- | --- |
| `requirements.md` | 优化后的范围、优先级、默认决策与验收边界 |
| `architecture.md` | 系统边界、组件关系和部署视图 |
| `data-flow.md` | 从创建研究到发布报告的数据流与信任边界 |
| `state-machine.md` | 状态、步骤、重试、取消和部分完成语义 |
| `data-model.md` | ER 图、表职责、唯一约束和审计规则 |
| `api.md` / `openapi.yaml` | Java API 契约及机器可读定义 |
| `analytics-api.md` | Java 调用 Python Analytics 的内部 API 约定 |
| `data-sources.md` | Provider 端口、来源追踪、新鲜度和许可门禁 |
| `calculation-methodology.md` | 量化、技术、基本面和情景计算口径 |
| `llm-design.md` | Claim/Evidence、Responses API、结构化输出和验证 |
| `security.md` | 威胁模型与安全控制 |
| `risk-register.md` | 风险、触发信号、缓解和责任阶段 |
| `implementation-plan.md` | 分阶段任务、入口条件、出口门禁和测试要求 |
| `roadmap.md` | MVP、完整 v1 与明确非目标 |
| `progress.md` | 当前实现、验证状态和受控限制 |
| `phase2-test-matrix.md` | Gate G2 已通过的持久任务与状态机验证记录 |
| `phase3-test-matrix.md` | Gate G3 Mock 纵向闭环的本地与远程通过证据 |
| `phase4-test-matrix.md` | Gate G4 完整量化、黄金数据和数值边界的通过证据 |
| `phase5-test-matrix.md` | Gate G5 Evidence 安全、Filing 检索和快照复现的通过证据 |
| `phase6-test-matrix.md` | Gate G6 Responses API、结构化输出、预算与失败降级的验证证据 |
| `adr/` | 关键、可追溯的架构决策 |

## 决策优先级

发生冲突时，按以下顺序裁决：

1. 不伪造金融数据、证据可追踪和安全边界；
2. 机器可读 OpenAPI/JSON Schema；
3. 本目录内已接受的 ADR 与方法论；
4. 文字示例和 UI 展示偏好。
