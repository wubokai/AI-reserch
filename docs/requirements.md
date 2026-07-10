# AI Quant Research Assistant 可执行需求基线

文档版本：0.1

状态：Accepted Phase 0 基线

日期：2026-07-09

适用范围：闭环 MVP 与完整 v1

## 1. 文档目的

本文把原始项目指令收束为可设计、可实现、可测试、可验收的规格。后续架构、API、数据库、代码和测试均应追溯到本文。

发生冲突时，优先级按以下顺序处理：

1. 数据真实性、用户安全和可审计性。
2. 已定义的发布验收门槛。
3. API 与数据契约的兼容性。
4. 功能数量和表现完整度。

因此，“必须提供三条 Bull/Bear 结论”等数量要求不得凌驾于“证据不足时禁止推断”之上。

## 2. 需求优先级定义

- Must：对应发布版本不可缺少；缺失即不能通过该版本 Gate。
- Should：强烈建议完成，但不阻塞闭环 MVP；原则上应进入完整 v1。
- Could：明确的后续能力，不进入闭环 MVP 或完整 v1 的硬验收。
- Won't for v1：明确非目标，用于防止范围失控。

## 3. 产品定义

AI Quant Research Assistant 是一个面向美股普通股和 ETF 的研究辅助平台。用户提交证券、研究问题、时间范围和分析选项后，系统异步收集或加载数据，通过确定性程序完成量化计算，建立 Evidence，生成并验证研究报告。

平台只提供研究辅助：

- 不执行真实交易。
- 不连接券商下单。
- 不承诺收益。
- 不输出保证性或无条件买卖指令。
- 不把模型推断伪装成事实。

## 4. 发布范围

### 4.1 闭环 MVP 的 Must

| ID | 能力 | 可验收定义 |
|---|---|---|
| MVP-M01 | Mock 证券 | 支持 MU、NVDA、RKLB，数据可重复，所有页面和导出均显示 “DEMO DATA — NOT REAL MARKET DATA” |
| MVP-M02 | 研究创建 | 用户可提交 symbol、query、benchmark、period、locale 和分析开关，并立即获得 researchId 与 QUEUED |
| MVP-M03 | 持久异步任务 | HTTP 请求不等待报告完成；进程重启后 QUEUED/RUNNING 任务可恢复 |
| MVP-M04 | 状态机 | 步骤有开始/结束时间、耗时、错误、attempt、幂等键；支持取消和从失败步骤重试 |
| MVP-M05 | Mock Provider | 行情、基本面、Filing、宏观均通过 Provider 接口提供，不与业务层耦合 |
| MVP-M06 | 核心量化 | Python 至少计算收益、CAGR、年化波动率、最大回撤、Sharpe、RSI、MACD 和三情景估值 |
| MVP-M07 | Evidence | 所有重要事实、数字和计算结果均注册 Evidence；报告只能引用已注册 ID |
| MVP-M08 | Claim 分类 | 所有可验证结论均使用 FACT、CALCULATION、INFERENCE、OPINION 之一 |
| MVP-M09 | Mock LLM | 无 OPENAI_API_KEY 时仍可基于 Evidence 生成确定、可测试的结构化报告 |
| MVP-M10 | 报告验证 | 验证 Evidence ID、数字、日期、类型和新鲜度；不合格 Claim 不得原样发布 |
| MVP-M11 | 前端闭环 | 支持创建、进度、报告、Evidence、情景、历史和错误/部分完成状态 |
| MVP-M12 | 导出 | 同一 report_json 可导出 Markdown、HTML 和 PDF |
| MVP-M13 | 数据质量 | 报告显示数据截至日期、生成时间、缺失、过期、冲突和限制 |
| MVP-M14 | Demo 用户 | dev-demo profile 提供固定演示用户；资源访问仍执行所有权校验 |
| MVP-M15 | 本地启动 | docker compose up --build 可启动 web、api、analytics、postgres、redis |
| MVP-M16 | 自动验证 | 核心单元测试、契约测试、Mock E2E 和 Docker smoke test 通过 |

### 4.2 完整 v1 在 MVP 之上的 Must

| ID | 能力 | 可验收定义 |
|---|---|---|
| V1-M01 | 完整量化指标 | 原始指令列出的收益、风险、技术、基本面、估值和情景指标均实现或返回结构化 NOT_AVAILABLE |
| V1-M02 | SEC Adapter 与 RAG | 获取、清洗、章节切分、全文检索、块级来源定位和 Evidence 注册 |
| V1-M03 | FRED Adapter | 宏观序列可配置、可缓存、可追踪来源并正确处理发布滞后 |
| V1-M04 | 行情 Adapter | 接入经许可评审通过的真实日线 adjusted OHLCV Provider |
| V1-M05 | 基本面 Adapter | 接入经许可评审通过的财务数据 Provider，并完成标准化映射 |
| V1-M06 | 真实 LLM | OpenAI Responses API、Structured Outputs、Prompt Version、预算和 Mock/Real 双实现 |
| V1-M07 | 韧性 | 外部调用具有超时、分类重试、指数退避、熔断、限流、缓存和降级 |
| V1-M08 | 成本控制 | 记录 Token、缓存 Token、价格版本、估算费用和任务预算；超预算停止非关键调用 |
| V1-M09 | 可观测性 | JSON 日志、Request ID、Research ID、延迟、错误、重试、缓存命中率、完成率、耗时和成本指标 |
| V1-M10 | 安全 | 密钥管理、资源授权、输入限制、SSRF 防护、HTML 清理、Prompt Injection 边界和日志脱敏 |
| V1-M11 | 历史版本 | 可列出和打开同一研究任务的报告版本 |
| V1-M12 | 完整 CI | Java、Python、Web、集成、E2E、Docker build 和 smoke test 全部作为必过项 |
| V1-M13 | 文档 | README、架构、API、数据源、计算方法、LLM、安全、路线图和 Provider 扩展指南完整 |

### 4.3 Should

- 支持 QUICK、STANDARD、DEEP 三种深度，并明确它们对数据跨度、检索量和 LLM 预算的影响。
- 支持 1y、3y、5y 周期；默认 5y。
- 完整 v1 可选增加 SSE 进度提示；断线后必须从 Java status API 补读，PostgreSQL 始终是权威来源。SSE 不进入闭环 MVP Gate。
- 报告内容默认跟随用户问题语言。
- 桌面端达到完整体验，移动端可完成创建、查看和导出。
- 报告图表支持价格、基准收益、回撤、财务趋势和技术指标。
- 对相同输入和相同数据快照复用中间结果。
- 提供报告版本差异说明。
- 提供安全的软删除与数据保留策略。

### 4.4 Could

- 向量检索或混合检索。
- 运行时 Provider 切换和管理员配置 UI。
- 企业级登录、组织、团队和细粒度 RBAC。
- 同行自动选择和同行估值。
- 分析师一致预期与 Forward P/E。
- 免费公开新闻 Adapter。
- 云原生部署、Kubernetes 和多区域高可用。

### 4.5 Won't for v1

- 实盘交易和券商下单。
- 自动投资组合执行。
- 期权定价。
- Level 2 Order Book。
- 高频和实时 Tick。
- 社交媒体情绪。
- 付费新闻抓取。
- 加密货币和中国 A 股。
- 复杂深度学习价格预测。
- Bloomberg 全量替代。

## 5. 默认产品决策

以下决策除非通过 ADR 显式修改，否则直接作为实现默认值。

| 主题 | 默认决策 |
|---|---|
| 首个闭环 | Mock First，不等待任何真实 API Key |
| UI 语言 | zh-CN |
| 报告语言 | request.locale 优先；未提供时跟随 query 语言 |
| 默认基准 | SPY；用户可显式选择 QQQ |
| MVP 周期 | 5y |
| v1 周期 | 1y、3y、5y，默认 5y |
| MVP 深度 | STANDARD |
| 行情粒度 | 美股日线 adjusted OHLCV |
| 价格收益口径 | 使用 adjustedClose 的简单收益率 |
| 年化天数 | 252 个交易日 |
| 活跃状态刷新 | 闭环 MVP 使用 TanStack Query 每 2 秒轮询 Java status API；进入终态后停止，PostgreSQL 始终是权威来源 |
| 异步执行 | PostgreSQL 持久任务队列、lease、heartbeat、数据库锁；不以 Spring @Async 作为可靠队列 |
| 执行顺序 | MVP 顺序执行步骤；后续只在不破坏状态可审计性的前提下并行取数 |
| 登录 | dev-demo 固定用户，无登录页；所有查询仍按 user_id 授权 |
| Provider 配置 | MVP/v1 默认由环境变量控制；Provider 页面只读 |
| 数据模式 | dataMode 只允许 REAL、MOCK、MIXED_TEST。development 未配置真实 Key 时显式使用 MOCK；任务创建后模式固定；REAL 不得静默混入 Mock；MIXED_TEST 只用于自动化集成测试，不得作为用户报告模式 |
| 检索 | v1 首选 PostgreSQL 英文全文搜索；向量检索仅留扩展接口 |
| 报告真源 | versioned report_json；Markdown、HTML、PDF 均由其确定性渲染 |
| PDF | Java 使用固定 HTML 模板和 OpenHTMLtoPDF；镜像内置 Noto Sans CJK 字体 |
| 数值计算 | Python 使用 float64 向量计算并按 calculation_version 规定舍入；Java 金额使用 BigDecimal |
| 跨服务契约 | OpenAPI 与 JSON Schema 为唯一契约源，三端不得各自发明不兼容 DTO |
| 缺失数据 | 返回 NOT_AVAILABLE、原因和影响，不使用 0 代替，不让 LLM 补齐 |
| 新闻 | 不属于 MVP/v1 Must；Catalyst 只能来自 SEC、宏观或其他已注册 Evidence |
| ETF | 使用能力矩阵；公司财务、10-K 或部分估值不适用时标记 NOT_APPLICABLE |
| 删除 | 默认软删除研究记录；保留必要审计记录 |

## 6. 核心领域模型

### 6.1 Claim

所有可验证报告结论必须结构化为 Claim：

- id
- text
- claimType：FACT、CALCULATION、INFERENCE、OPINION
- evidenceIds
- confidence
- numericReferences
- limitations
- validationStatus

Evidence 是来源或计算证据，claimType 是结论属性；不得把 claimType 固定到 Evidence 上。

### 6.2 Evidence

Evidence 至少包含：

- id
- researchId
- evidenceType
- title
- summary
- structuredValue
- unit
- sourceName
- sourceUrl
- sourceType
- provider
- publishedAt
- effectiveDate
- retrievedAt
- rawDataHash
- isPrimarySource
- freshnessStatus
- rawReference
- documentSection
- chunkId
- calculationVersion

LLM 只能引用 Registry 中已存在的 Evidence ID。

### 6.3 Source Snapshot

外部数据必须先落为可追溯快照，再产生 Evidence。快照应记录 Provider、请求范围、原始哈希、抓取时间、Schema Version 和解析状态。相同哈希不得重复存储。

### 6.4 Report

report_json 至少包含：

- schemaVersion
- reportVersion
- symbol、companyName、securityType
- asOfDate、generatedAt、locale
- executiveSummaryClaims
- companyOverviewClaims
- quantitativeSnapshot
- financialAnalysisClaims
- valuationClaims
- bullCase、bearCase、catalysts、risks
- scenarioAnalysis
- dataQuality
- limitations
- disclaimer
- evidenceIndex

纯叙事段落只能由已验证 Claim 和确定性数据模板渲染。

## 7. 任务状态与执行语义

项目状态沿用：

CREATED、QUEUED、RESOLVING_SECURITY、FETCHING_MARKET_DATA、FETCHING_FUNDAMENTALS、FETCHING_FILINGS、FETCHING_MACRO_DATA、VALIDATING_DATA、RUNNING_QUANT_ANALYSIS、ANALYZING_FUNDAMENTALS、BUILDING_EVIDENCE、GENERATING_REPORT、VALIDATING_REPORT、COMPLETED、PARTIALLY_COMPLETED、FAILED、CANCELLED。

执行要求：

- 每个步骤记录 attempt_count、started_at、completed_at、duration_ms、error_code、error_message。
- Worker 通过 lease 和 heartbeat 防止僵尸任务。
- 每个步骤以 researchId、stepType、inputHash、implementationVersion 形成幂等键。
- 重试默认从首个未成功或已失败步骤继续。
- 已成功步骤只有在输入哈希或实现版本变化时才允许重算。
- 自动重试与用户手动重试必须分别记录。
- 取消采用协作式语义：当前外部调用使用超时，调用结束后在下一步骤前终止。

终态规则：

- COMPLETED：所有请求的必需步骤成功，报告验证通过。
- PARTIALLY_COMPLETED：核心量化与安全报告可用，但一个或多个可降级模块缺失；或验证修复后移除了不合格 Claim。页面必须展示缺失和验证问题。
- FAILED：证券无法解析、核心行情不足、无法形成任何安全报告、持久化失败或不可恢复内部错误。
- CANCELLED：用户取消已被 Worker 确认。

Bull/Bear 数量规则：

- 目标为各三条。
- 只有足够 Evidence 时才生成。
- 不足三条时允许少于三条，并在 limitations 中记录 INSUFFICIENT_EVIDENCE。

## 8. 数据与时间规则

- 服务端时间统一以 UTC 存储。
- 前端按用户时区展示。
- 报告必须区分 asOfDate 与 generatedAt。
- 禁止把历史快照描述为当前数据。
- 日线收益使用 adjustedClose；原始 OHLCV 和 adjustedClose 同时保留。
- 标的与基准按共同交易日期 inner join。
- 重复日期按 Provider 最新 retrievedAt 去重，并记录 warning。
- 非正 adjustedClose 视为无效输入。
- 缺失值不得默认为 0。

Freshness 使用 FRESH、STALE、VERY_STALE、UNKNOWN。阈值必须按数据类型配置并版本化，不写死在 Claim 文本中。初始规则：

- 日线行情：距预期最新交易日不超过 2 个交易日为 FRESH，3 至 5 日为 STALE，超过 5 日为 VERY_STALE。
- Filing：以 filing/accession 是否为目标报告期最新公开版本判断，不能只看抓取时间。
- 财务数据：以对应财报发布日和财务期间判断。
- 宏观数据：以序列频率、发布时间表和修订状态判断。
- Mock 数据：freshness 可为 FRESH，但必须同时带 isDemoData=true，不能冒充真实当前数据。

## 9. 确定性量化方法基线

详细公式将在 calculation-methodology 文档维护；以下基线不可由 LLM 修改：

- Daily Return：adjustedClose 的 pct_change。
- Cumulative Return：各期 1 + return 的连乘减 1。
- CAGR：起止 adjustedClose 和实际日历年数计算。
- Annualized Volatility：日收益样本标准差乘 sqrt(252)。
- Daily Risk-Free Rate：(1 + annualRate)^(1/252) - 1。
- Sharpe：日超额收益均值除以其样本标准差，再乘 sqrt(252)。
- Sortino：日超额收益均值除以下行偏差，再乘 sqrt(252)。
- Historical VaR 95%：日收益 5% 分位数取正损失值，即 max(0, -q05)。
- Historical CVaR 95%：小于或等于 q05 的尾部日收益平均损失取正值。
- Beta：标的与基准共同日期的日收益协方差除以基准方差。
- Alpha：标的日均超额收益减 Beta 乘基准日均超额收益，再乘 252。
- Maximum Drawdown：财富曲线相对历史峰值的最小值，保持负数。
- RSI 14：Wilder smoothing。
- MACD：EMA 12 减 EMA 26；Signal 为 MACD 的 EMA 9。
- Bollinger Bands：SMA 20 加减 2 倍样本标准差。
- ATR 14：使用 high、low、previous close 的 True Range 和 Wilder smoothing。
- Volume Moving Average：20 日。
- 所有指标返回 sampleSize、periodStart、periodEnd、calculationVersion、warnings。

Trend Classification 必须由可测试规则给出，只能返回：

STRONG_UPTREND、UPTREND、RANGE、DOWNTREND、STRONG_DOWNTREND、INSUFFICIENT_DATA。

完整阈值在实现前写入 calculation-methodology 并通过表驱动测试；不得调用 LLM 分类。

## 10. 情景分析默认模型

MVP 使用单一、透明的 EBITDA 情景模型：

1. Forecast Revenue = Base Revenue × (1 + Revenue Growth)。
2. Forecast EBITDA = Forecast Revenue × Target EBITDA Margin。
3. Implied Enterprise Value = Forecast EBITDA × EV/EBITDA Multiple。
4. Implied Equity Value = Implied Enterprise Value - Net Debt。
5. Raw Implied Price = Implied Equity Value / Diluted Share Count。
6. Implied Price = max(0, Raw Implied Price)，同时保留原始值用于限制说明。
7. Upside/Downside = Implied Price / Current Price - 1。
8. Weighted Scenario Value = 各情景 Implied Price × Scenario Probability 的总和。

要求：

- Bull、Base、Bear 概率总和必须为 1，允许小于 1e-8 的浮点容差。
- 所有输入、币种、单位、基期、预测期和当前价格时点必须出现在报告。
- 结果只能称为情景隐含价值，不得称为确定目标价。
- 输入不适用时返回 NOT_AVAILABLE，不强行套用模型。

## 11. Confidence 与 Data Quality

二者必须由版本化、可测试规则生成，模型只能解释，不能任意指定数值。

confidence_v1：

- 单条 Evidence Quality = Source Weight × Freshness Weight × Trace Weight。
- Source Weight：Primary 1.00、已登记可信 Secondary 0.75、其他 0.50。
- Freshness Weight：FRESH 1.00、STALE 0.80、VERY_STALE 0.50、UNKNOWN 0.25。
- Trace Weight：哈希与精确定位器 1.00、仅哈希 0.85、追踪不完整 0.60。
- Claim 支持度取最多三个最相关 Evidence 的平均值，再乘类型系数：FACT 1.00、CALCULATION 1.00、INFERENCE 0.85、OPINION 0.65。
- 未解决来源冲突时上限 0.60；引用不存在时为 0 且阻止发布。

data_quality_v1：

- 40% Required Data Coverage
- 20% Freshness Coverage
- 20% Primary Source Coverage
- 20% Consistency Score

得分统一限制在 0 到 1，保留两位小数。NOT_APPLICABLE 从分母排除。Mock 数据必须显示 Demo 标记，支持度只代表 fixture 的证据完整性，不能解释为真实市场置信概率。

## 12. API 基线

Java API Must：

- POST /api/v1/research
- GET /api/v1/research
- GET /api/v1/research/{id}
- GET /api/v1/research/{id}/status
- POST /api/v1/research/{id}/retry
- POST /api/v1/research/{id}/cancel
- DELETE /api/v1/research/{id}
- GET /api/v1/research/{id}/evidence
- GET /api/v1/research/{id}/reports
- GET /api/v1/research/{id}/reports/{version}
- GET /api/v1/research/{id}/export?format=markdown|html|pdf
- GET /api/v1/securities/search
- GET /api/v1/providers/status
- GET /api/v1/health

POST /research Must 支持 Idempotency-Key。列表接口 Must 支持 page、size、sort、symbol、status、from、to。

Python API Must：

- POST /analytics/v1/returns
- POST /analytics/v1/risk
- POST /analytics/v1/technicals
- POST /analytics/v1/fundamentals
- POST /analytics/v1/valuation
- POST /analytics/v1/scenarios
- POST /analytics/v1/full-analysis
- GET /analytics/v1/health

涉及 Beta、Alpha、Correlation 和 Excess Return 的请求必须传入 benchmarkPrices；技术指标请求必须包含 adjusted OHLCV。

统一错误响应必须包含 timestamp、status、code、message、researchId 和 details。

## 13. 韧性与降级

可自动重试：

- HTTP 429、502、503。
- 网络超时。
- 明确标记为临时的服务错误。

不可自动重试：

- 401、403。
- 无效参数和无效证券。
- 永久 Schema 不兼容。
- Evidence 引用逻辑错误。

降级要求：

- 宏观缺失不阻断行情和量化。
- 非核心单项指标失败返回 warning。
- PDF 失败不影响网页、Markdown 和 HTML 报告。
- 真实 LLM 失败时保留已完成的量化分析；若 Mock LLM 被允许，则明确标记降级来源。
- 验证失败时先进行一次受约束修复；仍失败则移除不安全 Claim，能形成安全报告时标为 PARTIALLY_COMPLETED，否则 FAILED。

## 14. 安全与隐私

Must：

- 所有密钥来自环境变量，.env 不进入 Git。
- 日志屏蔽密钥、密码、认证 Token 和用户敏感数据。
- 所有按 ID 访问的资源检查 user_id，防止 IDOR。
- 限制 query、symbol、URL、导出大小和任务频率。
- 禁止任意 URL 抓取；外部 URL 必须满足 Provider 白名单。
- 外部 HTML 清理后再存储或展示。
- 检索文本与系统指令明确分隔。
- 工具调用使用白名单，外部内容不得修改系统指令。
- HTML/PDF 渲染必须转义用户和外部文本。

## 15. 测试与质量要求

每阶段至少执行该阶段涉及的：

- Java compile、unit test、integration test。
- Python Ruff、mypy、pytest。
- Web lint、type check、Vitest。
- 必要的 Playwright E2E。
- Docker build 与 smoke test。

测试不得依赖真实外部 API 或不固定的当前时间。Mock 数据使用固定种子、固定时钟和黄金结果。不得删除失败测试或降低断言以制造通过。

## 16. 非功能目标

- Controller 不包含业务逻辑。
- DTO 与 Entity 分离。
- 外部 Provider DTO 不进入领域层。
- 金额使用 BigDecimal/DECIMAL。
- 所有公共 Python 函数有类型标注。
- TypeScript strict mode，不滥用 any。
- 重复请求、重试和进程重启不能产生重复行情、重复报告版本或重复费用记录。
- 日志和指标能按 Request ID 与 Research ID 串联 Java、Python 和外部调用。

## 17. Phase 7 前仍未选择的事项

以下事项有意延后，不能在早期代码中硬编码供应商假设：

- 真实 Market Data Provider。
- 真实 Fundamental Data Provider。
- 是否购买分析师一致预期，从而支持 Forward P/E。
- 是否获得同行数据或由领域规则生成同行集合。
- 数据许可是否允许长期缓存、数据库持久化、页面展示和 PDF/HTML 再分发。
- Provider 的配额、速率、历史深度、公司行动调整、修订策略、SLA 和成本。

SEC 与 FRED 可按官方接口设计 Adapter，但仍须遵守其身份标识、访问频率和使用政策。Phase 7 决策流程详见 implementation-plan 与 risk-register。
