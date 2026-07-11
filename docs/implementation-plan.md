# AI Quant Research Assistant 实施计划

文档版本：0.1

状态：Phase 0–6、8–9 已完成；Gate G0–G6、G8 与 G9 工程 Gate 已通过；G7 等待 Market 外部门禁

日期：2026-07-10

## 1. 实施原则

1. 先完成安全、可运行的纵向闭环，再扩展指标和真实数据源。
2. 每次只推进一个可验证阶段。
3. 每个阶段结束时，仓库必须仍可构建、测试和启动。
4. 不生成大量未经验证的占位代码。
5. 不在关键路径遗留 TODO。
6. 不删除测试、降低断言或屏蔽错误来制造通过。
7. 数据真实性和 Evidence 约束优先于报告篇幅或功能数量。
8. 外部 API 不确定性必须封装在 Adapter 内。
9. 真实 API Key 缺失时使用 Mock，不中断整体开发。
10. 对已有代码先检查、后修改，保留用户有价值的变更。

## 2. 发布 Gate 总览

| Gate | 目标 | 通过条件 |
|---|---|---|
| G0：Phase 0 | 需求和架构可执行 | Must/Should/Could、默认决策、状态机、ER、API、数据流、方法、风险和计划均完成且无未处置 P0 冲突 |
| G1：Skeleton | 三服务和基础设施可启动 | web、api、analytics、postgres、redis 均健康；基础 CI 通过 |
| G2：Durable Workflow | 任务可持久、可恢复 | 创建、轮询、重启恢复、重试、取消、幂等测试通过 |
| G3：Closed-loop MVP | Mock 完整演示可用 | MU 从创建到报告、Evidence、情景、三种导出和历史全部通过 |
| G4：Quant Complete | 确定性分析完整 | 全指标或结构化 NOT_AVAILABLE；边界与黄金测试通过 |
| G5：Evidence Safe | 报告不可绕过证据 | Claim/Evidence、数字、日期、类型、Freshness、部分完成验证通过 |
| G6：Real LLM | 真实模型可控 | Structured Outputs、预算、重试、Mock/Real、修复和失败降级通过 |
| G7：Real Data | 真实 Provider 合规可用 | 许可评审、Adapter Contract Test、缓存、限流、错误和来源追踪通过 |
| G8：Product Complete | 完整前端与历史版本可用 | 所有页面、图表、版本、Evidence、Data Quality 和导出体验通过 |
| G9：v1 Release | 完整 v1 可交付 | 验收矩阵所有 v1 Must 通过，CI 全绿，文档可供新开发者独立启动 |

任何 Gate 未通过时，不进入下一阶段的完整实现。允许为验证架构制作最小探针，但不得以探针替代前置 Gate。

## 3. Phase 0：需求与架构

状态：已完成（Gate G0 通过）

### 3.1 交付物

- docs/requirements.md
- docs/risk-register.md
- docs/implementation-plan.md
- docs/architecture.md
- docs/api.md
- docs/data-sources.md
- docs/calculation-methodology.md
- docs/llm-design.md
- docs/security.md
- docs/roadmap.md
- 架构图、数据流图、任务状态转移图和数据库 ER 图
- OpenAPI 初稿
- Research Report、Claim、Evidence、Analytics JSON Schema 初稿
- ADR：
  - PostgreSQL 持久任务队列
  - Mock First
  - OpenAPI/JSON Schema 唯一契约源
  - 报告 JSON 单一真源
  - PDF 渲染方案
  - dev-demo 认证边界

补充机器契约：`packages/shared-schemas/analytics/` 定义 adjusted OHLCV、benchmarkPrices 和分析结果。

### 3.2 必须确认的决策

- 闭环 MVP 与完整 v1 的边界。
- Must/Should/Could。
- 状态机、终态和 PARTIALLY_COMPLETED 语义。
- Claim/Evidence/Source Snapshot 的边界。
- 量化公式、符号、精度、最小样本和 warning。
- 情景估值公式。
- Freshness、Confidence 和 Data Quality 版本化规则。
- REST 错误格式、分页、版本和幂等语义。
- 安全、降级、预算和审计要求。
- Phase 7 前不选择真实 Market/Fundamental Provider。

### 3.3 Gate G0

- 原始需求中的冲突均已记录并有默认解析。
- 所有 MVP Must 均有责任阶段和验收方式。
- 不存在依赖真实 API Key 才能开始的工作。
- 不存在要求 LLM 计算金融数字的契约。
- OpenAPI/Schema 能表达 benchmarkPrices、adjusted OHLCV、Claim 和 Evidence。
- 风险登记册中所有 Phase 0 严重风险已有缓解。

## 4. Phase 1：项目骨架

状态：已完成；GitHub Actions 已通过 Docker build、五服务 Compose 启动和 smoke，Gate G1 通过。

### 4.1 实施内容

- 创建 Monorepo 目录。
- 创建 Java 21 Spring Boot API。
- 创建 Python 3.12 FastAPI Analytics。
- 创建 Next.js TypeScript Web。
- 配置 PostgreSQL、Redis、Docker Compose。
- 添加 Health/Readiness。
- 添加基础 GitHub Actions。
- 添加 .env.example、.gitignore、Makefile 和基础 README。
- 固定依赖版本和基础格式化、Lint、类型检查。

### 4.2 测试

- Java context load 和 health test。
- Python health test、Ruff、mypy。
- Web lint、type check、基础组件 test。
- Compose 启动与服务健康 smoke test。
- Docker image build。

### 4.3 Gate G1

- docker compose up --build 后三个应用和两个依赖均健康。
- Web 可显示 API 与 Analytics 健康状态。
- CI 在无真实 API Key 环境通过。
- 容器不把密钥写入镜像或日志。

## 5. Phase 2：数据库、持久任务与状态机

### 5.1 实施内容

- Flyway 初始迁移。
- User、Security、ResearchProject、ResearchStep 基础模型。
- 统一枚举、错误码和错误响应。
- PostgreSQL 持久任务队列。
- Worker lease、heartbeat、超时回收。
- 步骤幂等键、输入哈希和 implementationVersion。
- 创建、列表、详情、状态、重试、取消、软删除 API。
- dev-demo profile 与资源所有权校验。
- Request ID、Research ID 日志上下文。

### 5.2 测试

- 状态合法/非法转换。
- 相同 Idempotency-Key 不重复创建。
- Worker 竞争、锁和 lease 过期恢复。
- 进程重启后任务恢复。
- 已成功步骤不重复执行。
- 可重试/不可重试错误。
- 取消和软删除。
- IDOR 防护。
- PostgreSQL、Redis Testcontainers。

### 5.3 Gate G2

- 创建接口在规定时间内返回 QUEUED，不等待执行完成。
- 重启、重复消费和用户重试不会重复写入成功步骤。
- 状态、progress、currentStep、耗时和错误可查询。
- 无效证券、非法状态和越权访问返回统一错误。

## 6. Phase 3：Mock 纵向闭环

状态：已完成；Gate G3 已通过。远程证据见 [`phase3-test-matrix.md`](./phase3-test-matrix.md)。

这是第一个真正的产品 Gate，不允许只做 Provider 或 UI 的孤立演示。

### 6.1 实施内容

- MU、NVDA、RKLB 的固定五年日线 adjusted OHLCV。
- 基础财务数据、两份模拟 Filing、宏观序列。
- Mock Market/Fundamental/Filing/Macro Provider。
- 最小 Python Analytics：Return、CAGR、Volatility、Maximum Drawdown、Sharpe、RSI、MACD、Scenario。
- Source Snapshot、Evidence、Claim、Quant Result、Report、LLM Call 基础表。
- Deterministic Mock LLM。
- 报告验证器第一版。
- 首页、进度、报告、Evidence、情景、历史。
- Markdown、HTML、PDF 确定性导出。
- Demo 标记贯穿数据、页面和导出。

### 6.2 测试

- 三个 Mock 证券结果可重复。
- 所有关键数值能反向定位 Evidence。
- Mock LLM 无 Evidence 时不能生成事实。
- unsupported Claim 被拒绝或安全删除。
- PDF 中包含中文、Demo 水印、免责声明和来源。
- Playwright 端到端：
  - 打开首页。
  - 输入 MU 和指定研究问题。
  - 创建任务。
  - 观察进度。
  - 打开报告和 Evidence。
  - 查看 Bull/Base/Bear。
  - 导出三种格式。
  - 从历史重新打开。

### 6.3 Gate G3

- MVP-M01 至 MVP-M16 全部通过。
- 无 OPENAI_API_KEY、无真实数据 Key 时闭环成功。
- 进度页仅依赖 TanStack Query 每 2 秒轮询 Java status API，不要求 SSE 或额外事件分发设施。
- 闭环任务的 dataMode 为 MOCK；MIXED_TEST 仅用于自动化集成测试且不得生成用户可见报告。
- 任意 Mock 数据不得被描述为真实当前市场数据。
- 报告不存在无 Evidence 的重要事实或数字。
- 失败路径至少覆盖 Provider timeout、Analytics failure、Schema error、Evidence failure 和重试成功。

## 7. Phase 4：完整 Python 量化服务

### 7.1 实施内容

- 完整收益指标。
- 完整风险指标。
- 完整技术指标和 Trend Classification。
- 完整基本面比率。
- 数据可用时的估值指标。
- EBITDA 三情景模型和敏感性。
- 所有 Analytics 端点和 versioned Pydantic Schema。
- calculation-methodology 完整公式和示例。

### 7.2 测试

- 正常数据。
- 空数组、单点、缺失值。
- 零/负价格。
- 重复和乱序日期。
- 标的/基准日期不一致。
- 样本不足。
- 负利润、负权益、除零。
- 概率不等于 1。
- 黄金数据和手算样例。
- Java/Python Contract Test。

### 7.3 Gate G4

- 所有原始指标均有实现或明确 NOT_AVAILABLE 规则。
- 每项结果含样本、区间、版本和 warning。
- Trend Classification 无 LLM 依赖。
- Ruff、mypy、pytest 全通过。

## 8. Phase 5：Evidence、报告验证与 SEC 检索基础

### 8.1 实施内容

- 完整 Source Snapshot。
- Evidence Registry。
- Claim 与多对多 Evidence 关联。
- numericReferences 与日期引用。
- Freshness、Confidence、Data Quality 确定性评分。
- 数值、日期、ID、类型、新鲜度和来源支持验证。
- 一次受约束修复和安全删减。
- Filing HTML 清理、章节切分和 chunk。
- PostgreSQL 全文搜索。
- Evidence API 和前端 Evidence Drawer 联动。

### 8.2 测试

- 缺失 ID、错误 ID。
- Evidence 数值与 Claim 数值不一致。
- 日期不一致。
- 过期数据未提示。
- FACT 由推断证据支持。
- 同一 Evidence 支持多个不同类型 Claim。
- 不安全 Claim 不出现在 partial 报告。
- Prompt Injection fixture 不改变工具或系统行为。
- SEC 代表性 Filing 章节覆盖率。

### 8.3 Gate G5

- 报告中所有重要事实、数字和计算均可定位。
- PARTIALLY_COMPLETED 仍是安全报告，不是“带已知错误的报告”。
- 旧报告绑定原始 Snapshot，不随缓存更新而变化。

## 9. Phase 6：真实 LLM

状态：已完成；Gate G6 已通过。实现与证据见 [`phase6-test-matrix.md`](./phase6-test-matrix.md)。

### 9.1 实施内容

- ResearchLanguageModel 接口。
- OpenAI Responses API Adapter。
- Strict Structured Outputs。
- Research Plan、Filing Analysis、Fundamental Narrative、Risk、Final Report 和 Validation Schema。
- Prompt Versioning。
- Mock/Real 双实现。
- Token、缓存 Token、价格版本、延迟和成本。
- 任务预算预留、结算和停止规则。
- 一次修复策略。

### 9.2 测试

- Mock LLM 固定输出。
- Real Adapter 使用 HTTP mock，不调用真实网络。
- Schema 不合法。
- 超时、429、5xx。
- 预算不足。
- 重复调用幂等。
- Evidence 白名单。
- 外部文本 Prompt Injection。

### 9.3 Gate G6

- 模型名称和价格配置不硬编码在业务逻辑。
- 模型无法新增未注册 Evidence。
- 超预算不触发非关键调用。
- LLM 最终总结失败时保留安全量化结果。

## 10. Phase 7：真实数据源

接入顺序固定为 SEC、FRED、Market、Fundamental。每次只接入一个 Adapter，通过 Gate 后再接下一个。

### 10.1 Provider 许可 Gate

Market 和 Fundamental Provider 尚未选择。选择前必须完成：

- 功能覆盖矩阵。
- 数据质量与历史深度样本。
- 公司行动和 adjusted close 说明。
- ETF、类别股、退市证券覆盖。
- Rate Limit、并发、配额、SLA 和成本。
- 数据持久化、缓存、展示、派生计算和导出许可。
- 品牌、归属、延迟声明要求。
- 数据修订和历史快照政策。
- 法务/条款确认记录。

不满足 requirements 与 risk-register 中 Stop 条件的 Provider 不得接入。

### 10.2 每个 Adapter 的实施模板

1. Adapter 与配置。
2. 外部 DTO 到领域模型映射。
3. Contract Test 与 fixture。
4. Timeout、Retry、Backoff、Circuit Breaker、Rate Limiter。
5. Cache Key 与 TTL。
6. Source Snapshot 和 rawDataHash。
7. Freshness 规则。
8. 错误映射和降级。
9. Provider status 指标。
10. data-sources 与 README 更新。

### 10.3 Gate G7

- 无供应商对象泄漏到领域层。
- dataMode 枚举固定为 REAL、MOCK、MIXED_TEST。development 无 Key 时可显式以 MOCK 启动；已创建的 REAL 任务不得静默回退或混入 Mock；MIXED_TEST 仅允许自动化集成测试使用。
- Rate Limit 和超时场景可重复测试。
- 旧报告可复现。
- 页面和导出符合来源归属要求。
- Market/Fundamental Provider 均有许可决策记录；未通过者保持未启用。

## 11. Phase 8：完整前端产品

实施状态：完成；Gate G8 已通过本地与远端全仓终验。

### 11.1 实施内容

- Dashboard 和最近研究。
- 完整 Research Form。
- 进度、步骤耗时、错误、重试、取消。
- 报告模块和全部图表。
- Claim 与 Evidence 交互。
- Data Quality、缺失、过期和冲突。
- 历史筛选、分页、搜索和报告版本。
- Provider 状态只读页。
- Markdown、HTML、PDF 导出状态。
- 桌面完整、移动基础兼容。

### 11.2 测试

- Loading、Empty、Error、Partial、Completed。
- 创建、取消、重试。
- Evidence Drawer。
- 历史筛选和版本。
- 导出成功和失败。
- 响应 Schema 被 Zod 拒绝。
- 基础可访问性和移动视口。

### 11.3 Gate G8

- 前端控制台无明显错误。
- 所有用户操作有 Loading、成功和错误反馈。
- 数据过期、Demo、Partial 和 Disclaimer 不会被视觉隐藏。

## 12. Phase 9：发布硬化

实施状态：已完成；远端全仓 CI、三应用镜像扫描、最小权限 Compose、闭环 smoke 和连续成功记录均通过，Gate G9 工程硬化完成。

### 12.1 实施内容

- JSON 日志和敏感信息审计。
- Prometheus 指标和关键阈值。
- 缓存命中率与性能优化。
- SSRF、XSS、Prompt Injection、IDOR 和输入限制。
- PDF 字体、分页、大小限制和远程资源禁用。
- 全量 CI、E2E 和 Compose smoke。
- README、截图位置、架构、API、Provider 扩展、常见错误。
- 依赖锁定、漏洞检查和容器最小权限。

### 12.2 Gate G9

- 完整 v1 验收矩阵通过。
- CI 连续通过且测试不依赖真实外部服务。
- 新开发者仅按 README 能启动 Mock 流程。
- 所有严重和高风险均关闭、接受或有明确的发布说明。

## 13. 验收矩阵

标记说明：M 表示闭环 MVP 必须通过；V 表示完整 v1 必须通过；— 表示该版本不要求。

| ID | 验收项 | MVP | v1 | 主要验证方式 | 责任阶段 |
|---|---:|:---:|:---:|---|---|
| AC-01 | docker compose up --build 启动全部服务 | M | V | Compose smoke、health | 1 |
| AC-02 | 创建研究立即返回 researchId 与 QUEUED | M | V | API 集成测试 | 2 |
| AC-03 | 可查看进度、步骤和耗时 | M | V | API + Playwright | 2-3 |
| AC-04 | 任务重启后恢复 | M | V | Worker 集成测试 | 2 |
| AC-05 | 从失败步骤重试且不重做成功步骤 | M | V | 状态机/幂等测试 | 2 |
| AC-06 | 可取消任务 | M | V | API/Worker 测试 | 2 |
| AC-07 | Mock 模式完成端到端研究 | M | V | Playwright E2E | 3 |
| AC-08 | MU/NVDA/RKLB 均标记 Demo | M | V | E2E、导出检查 | 3 |
| AC-09 | 核心 Python 指标和边界测试 | M | V | pytest | 3-4 |
| AC-10 | 原始指令全部指标或 NOT_AVAILABLE | — | V | pytest、方法审计 | 4 |
| AC-11 | 关键事实和数字均有 Evidence | M | V | Report Validator | 3、5 |
| AC-12 | LLM 不直接计算或伪造数字 | M | V | 负向测试、Schema | 3、5-6 |
| AC-13 | Bull/Base/Bear 透明展示输入和结果 | M | V | 单元测试、E2E | 3-4 |
| AC-14 | Evidence 验证失败安全降级 | M | V | 集成测试 | 3、5 |
| AC-15 | 历史报告可重新打开 | M | V | E2E | 3 |
| AC-16 | 报告版本可列出和打开 | — | V | API + E2E | 5、8 |
| AC-17 | Markdown、HTML、PDF 导出 | M | V | 导出集成测试 | 3、9 |
| AC-18 | PDF 失败不影响其他报告 | M | V | 故障注入 | 3 |
| AC-19 | Provider 失败可降级 | M | V | Mock 故障注入 | 3、7 |
| AC-20 | SEC 数据与块级来源 | — | V | Contract/RAG 测试 | 5、7 |
| AC-21 | FRED 宏观数据来源追踪 | — | V | Contract Test | 7 |
| AC-22 | 真实行情 Provider 许可和接入 | — | V | 许可记录 + Contract Test | 7 |
| AC-23 | 真实基本面 Provider 许可和接入 | — | V | 许可记录 + Contract Test | 7 |
| AC-24 | API Key 仅来自环境变量 | M | V | Secret scan、配置测试 | 1、9 |
| AC-25 | 资源所有权和 IDOR 防护 | M | V | 安全集成测试 | 2、9 |
| AC-26 | Prompt Injection 不改变规则/工具 | M | V | 对抗 fixture | 5-6、9 |
| AC-27 | 成本和任务预算 | — | V | 预算并发测试 | 6 |
| AC-28 | JSON 日志和 Prometheus 指标 | 基础 | V | 日志/指标集成测试 | 2、9 |
| AC-29 | CI 全部通过 | 基础 | V | GitHub Actions | 1-9 |
| AC-30 | README 可指导新开发者启动 | M | V | 干净环境演练 | 3、9 |
| AC-31 | 前端无明显控制台错误 | M | V | Playwright | 3、8 |
| AC-32 | 后端无未映射异常响应 | M | V | API 错误测试 | 2、9 |
| AC-33 | 不存在硬编码金融结论 | M | V | 代码审查、测试 | 3、6 |
| AC-34 | 不包含交易或下单功能 | M | V | 路由/依赖审计 | 全程 |
| AC-35 | 页面和导出含免责声明 | M | V | E2E、导出检查 | 3、8 |

## 14. 每阶段 Definition of Done

一个阶段只有同时满足以下条件才算完成：

- 交付物已提交且不是占位文本。
- 新增/修改代码有对应测试。
- 编译、单测、Lint、类型检查通过。
- 所需集成/E2E/smoke 通过。
- 未通过的测试未被删除、跳过或弱化。
- API 或 Schema 变化已同步文档和消费者。
- 新增错误有统一错误码。
- 新增外部调用有超时和日志脱敏。
- 新增金融数字可追溯到数据或确定性计算。
- README 和阶段进度已更新。
- 已记录修改内容、原因、测试结果和剩余限制。

## 15. 变更控制

以下变更必须先更新文档或 ADR，再改代码：

- Claim、Evidence、Report Schema。
- REST 或 Analytics API Contract。
- 状态机与终态语义。
- 量化公式、精度或 calculationVersion。
- Provider 和许可。
- LLM 模型、Prompt Version 或预算策略。
- PDF 引擎。
- 认证与资源所有权边界。

破坏性 API 变更必须增加版本或迁移方案，不得静默改变现有 Contract。

## 16. 当前下一步

Phase 0–6 与 Gate G0–G6 已完成。Phase 7 已完成 SEC EDGAR、FRED、SEC
Companyfacts/XBRL 和统一 Provider Runtime 工程检查点。Gate G7 的下一步固定为：

1. 取得覆盖持久化、外部展示、报告导出和再分发的 Market Provider 书面权利；
2. 许可通过后接入 Market Adapter；未通过时保持禁用；
3. Provider-neutral REAL 创建/解析/发布边界已完成；接入 Market 后执行全 REAL Worker 与旧报告复现终验，不允许静默混入 Mock；
4. SEC/FRED 的 UI、Markdown、HTML 与 PDF 来源归属已完成；Market 归属随许可后的 Adapter 一并验证；
5. 通过最终 Web、Analytics、API/Testcontainers、secret scan 与 Compose 终验后关闭 Gate G7。

Phase 9 发布硬化、远端 Gate G9 工程终验和文档证据已完成。当前可自主工程范围已经收口；
Gate G7/AC-22 的 Market 外部许可及生产配置阻塞保持独立记录，不允许通过 Mock 或局部真实数据绕过，集中输入见 [`external-inputs.md`](./external-inputs.md)。
