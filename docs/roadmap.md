# 路线图与范围边界

## 产品北极星

用户能从一个研究问题出发，得到一份可重放、可验证、可解释来源和限制的美股/ETF 研究报告。平台的价值是“证据组织 + 确定性分析 + 受约束的模型综合”，不是预测价格或替代持牌投资顾问。

## 闭环 MVP

MVP 先证明一条完整纵向路径，不追求所有指标和真实来源：

- MU、NVDA、RKLB 三个确定性 Mock Security，另含 QQQ/SPY 基准 fixture；
- 固定五年日线 adjusted OHLCV、基础财务、两份 Mock Filing 和宏观序列；
- PostgreSQL 持久任务、lease/heartbeat、步骤幂等、重试和协作式取消；
- 核心收益、CAGR、波动率、最大回撤、Sharpe、RSI、MACD 与情景分析；
- SourceSnapshot、Evidence、Claim、数字引用与确定性验证器；
- Mock LLM 生成稳定、可测试的 Claim 报告；
- 首页、进度、报告、Evidence、情景、历史和只读 Provider 状态；
- Markdown、HTML、PDF 导出；
- 单 demo 用户但保留严格 ownership；
- Docker Compose、核心单测、集成测试、Playwright 演示 E2E 和 smoke test。

MVP Gate G3 最初只实现 `STANDARD/5y`。Phase 9 需求审计已补齐 `QUICK/STANDARD/DEEP`、`1y/3y/5y` 和最长五年的显式日期范围；各深度具有可测试的 Filing、Evidence、Calculation 与 LLM 工具轮次预算。

## 完整 v1

- 补齐方法文档列出的收益、风险、技术、基本面和估值指标；
- SEC EDGAR、FRED、一个经许可的行情 Provider 和一个基本面 Provider；
- SEC 章节识别、chunk、PostgreSQL 全文检索与本地 Evidence 工具调用；
- OpenAI Responses API、严格 Structured Outputs、Prompt 版本、成本预算和一次修复（Phase 6 已实现）；
- 完整图表、Evidence 联动、报告版本和 Data Quality 解释；
- Resilience4j、缓存、限流、熔断、结构化日志、Prometheus 和安全加固；
- 三种导出、中文字体与分页回归测试；
- 全部验收测试与可重复 Compose 演示。

Forward P/E、同行/历史估值和事件日历只有在所选 Provider 提供可追踪且允许展示/导出的数据时才启用；否则正式显示 `NOT_AVAILABLE`。

## Phase 7 前的决策门

以下选择不阻塞 Mock 闭环，但接真实数据前必须由产品/预算负责人确认：

1. 行情 Provider 与基本面 Provider 的供应商、套餐和速率；
2. 历史数据存储、Redis 缓存、派生分析、用户展示、PDF 导出和再分发许可；
3. 正式用户认证方式、租户模型和部署目标；
4. 数据/报告/Prompt 诊断的保留期限；
5. 生产部署使用的具体模型、质量评测集和单任务预算；Adapter 与安全预算边界已在 Phase 6 完成，模型 slug 不写死在代码或示例中。

## 明确非目标

- 实盘/模拟下单、券商连接、仓位管理和自动交易；
- 期权定价、Level 2、Tick、HFT 与延迟敏感执行；
- 加密货币、A 股和完整全球证券主数据；
- 社交媒体情绪、付费新闻绕过或付费墙抓取；
- 深度学习价格预测、确定性目标价或收益承诺；
- Bloomberg/FactSet/Capital IQ 的完整替代；
- MVP 中的向量数据库、Kafka、Kubernetes 或多区域部署；
- 让 LLM 任意访问互联网、运行来源代码或自行生成金融数字。

## 后续候选

在完整 v1 稳定后评估：

- 真正多用户/OIDC、团队共享、评论和审批；
- 可插拔向量检索与多 Filing 对比；
- 事件日历与合规新闻 Provider；
- 报告差异、数据修订追踪和定时刷新；
- 用户自定义但受约束的指标组合与敏感性分析；
- 组合层研究（仅分析，不下单）；
- 独立生产部署模板和灾难恢复自动化。

每个候选功能必须先回答：是否改善证据质量、是否增加金融误导风险、是否有数据许可、是否能被测试、是否能在现有边界内降级。
