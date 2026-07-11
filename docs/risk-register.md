# AI Quant Research Assistant 风险登记册

文档版本：0.4

状态：Gate G6 已通过；Phase 7+ 前向风险持续跟踪

日期：2026-07-10

## 1. 评估方法

可能性：

- 高：在没有主动控制时很可能发生。
- 中：在特定条件下可能发生。
- 低：不常见，但仍需监控。

影响：

- 严重：可能导致错误金融结论、数据泄露、不可恢复数据问题或发布失败。
- 高：关键流程不可用、重大返工或验收失败。
- 中：局部降级、性能或体验问题。
- 低：不影响核心闭环。

风险级别由可能性与影响综合判断。严重和高风险未有明确缓解措施前，不允许通过对应阶段 Gate。

## 2. 活跃风险

| ID | 风险 | 可能性 | 影响 | 触发信号 | 预防与缓解 | 责任阶段 | 状态 |
|---|---|---:|---:|---|---|---|---|
| R-001 | 项目范围同时覆盖 MVP、完整 v1 和生产级能力，导致长期没有可运行闭环 | 高 | 高 | 大量模块只有占位代码；连续阶段无法演示创建到报告 | Mock First；Phase 3 必须形成纵向闭环；Should/Could 不得阻塞 Must | Phase 0-3 | 已缓解 |
| R-002 | LLM 伪造价格、财务数字、日期或增长率 | 高 | 严重 | 报告出现 Evidence 中不存在的数值；自由文本包含未结构化数字 | Claim/Evidence 模型；严格输出；数值引用；确定性验证器；不合格 Claim 隔离；LLM 不计算指标 | Phase 3-6 | Phase 6 已缓解；模型/Schema 变更时重评 |
| R-003 | 报告纯文本字段绕过四类 Claim 和 Evidence 要求 | 高 | 严重 | executive summary 或 conclusion 无 evidenceIds | 所有可验证叙事改为 Claim；模板只渲染已验证 Claim | Phase 0、5 | 已缓解 |
| R-004 | 指标口径含糊造成结果看似正确但实际错误 | 高 | 严重 | Java/Python/文档得到不同 CAGR、VaR、Alpha；测试只检查不报错 | 固化 calculation_version、公式、符号、年化、对齐和舍入；黄金数据测试 | Phase 0、4 | 已缓解 |
| R-005 | 使用内存线程或 Spring @Async 导致任务在重启时丢失 | 高 | 高 | 重启后 RUNNING 任务永久卡住；无 Worker 所有权 | PostgreSQL 持久队列、lease、heartbeat、超时回收和锁 | Phase 2 | 已缓解 |
| R-006 | 步骤重试产生重复数据、报告版本或 LLM 费用 | 高 | 高 | attempt 增加时重复行；相同调用多次计费 | 步骤幂等键、输入哈希、唯一约束、调用哈希、预算预留和事务边界 | Phase 2、6 | Phase 6 已缓解 |
| R-007 | 行情或基本面 Provider 许可不允许持久化、缓存、展示或导出 | 中 | 严重 | 服务条款限制再分发；供应商要求删除历史数据 | Phase 7 前不选定；完成书面许可矩阵；不满足存储/导出权即停止接入 | Phase 7 | 延后决策 |
| R-008 | Provider 限流、超时或响应 Schema 变化破坏研究流程 | 高 | 高 | 429/5xx 增多；字段缺失；解析失败 | Adapter 隔离、Contract Test、版本化解析、超时、分类重试、熔断、Rate Limiter、Mock 降级 | Phase 7 | 开放 |
| R-009 | Java、Python、TypeScript 契约漂移 | 高 | 高 | 相同字段命名/枚举不同；前端收到无法解析响应 | OpenAPI/JSON Schema 唯一真源；生成或校验 DTO；CI 契约测试 | Phase 0-3 | 已缓解 |
| R-010 | SEC 或网页内容包含 Prompt Injection 并诱导模型改变规则或调用工具 | 中 | 严重 | Filing 文本出现指令；模型尝试引用未授权来源 | 外部文本不可信标记、指令/数据隔离、工具白名单、Schema、Evidence 验证、禁止执行抓取内容命令 | Phase 5-7 | Phase 5 边界已缓解；Phase 7 真实接入开放 |
| R-011 | API Key、密码或 Token 进入代码、Git、日志或错误响应 | 中 | 严重 | secret scan 命中；日志出现 Authorization | 环境变量、.env.example、日志脱敏、错误过滤、CI secret scan、测试伪密钥 | Phase 1、9 | 开放 |
| R-012 | 证券资源缺少所有权检查，用户可通过 UUID 读取或删除他人报告 | 中 | 严重 | 按 ID Repository 查询不含 user_id | Service 层统一 currentUser；Repository 查询包含 owner；IDOR 集成测试 | Phase 2、9 | 缓解中 |
| R-013 | ETF 被套用普通公司财务和 SEC 分析，产生错误结论 | 高 | 高 | ETF 报告出现 ROIC、公司客户、10-K 结论 | SecurityType 能力矩阵；NOT_APPLICABLE；按类型选择报告模板和步骤 | Phase 2-5 | 计算与报告均失败关闭；完整 ETF 模板仍后续扩展 |
| R-014 | 数据过期、缺失或冲突却在报告中被描述为当前数据 | 中 | 严重 | asOfDate 晚于 Evidence；页面无 stale 标记 | Freshness 规则版本化；asOfDate 取证据有效期；Data Quality；冲突进入 limitations | Phase 3-7 | Phase 5 确定性门禁已缓解；真实日历/Provider 开放 |
| R-015 | 公司行动、复权方式或交易日对齐错误，扭曲收益和 Beta | 中 | 严重 | 拆股附近出现异常收益；标的和基准长度不一致 | adjustedClose 为收益真源；保留原始 OHLCV；共同日期 inner join；公司行动测试 | Phase 4、7 | 计算层已缓解；真实 Provider 开放 |
| R-016 | 财务指标概念映射错误，尤其 SEC XBRL 标签和正负号 | 高 | 严重 | 同一公司年份指标跳变；现金流符号相反 | 标准指标字典、映射版本、来源 concept 记录、跨期校验、人工黄金样例 | Phase 4、7 | 计算层已缓解；Provider 映射开放 |
| R-017 | Forward P/E、同行估值、催化剂缺少数据源却被强制输出 | 高 | 高 | 报告使用未注册预测或虚构日期 | 默认 NOT_AVAILABLE；不作为 MVP/v1 硬数据项；仅在许可数据源可用时启用 | Phase 0、7 | 缓解中 |
| R-018 | Bull/Bear 各三条的数量要求驱动模型编造 | 高 | 严重 | Evidence 不足仍固定生成三条 | 数据真实性优先；允许少于三条；记录 INSUFFICIENT_EVIDENCE | Phase 0、5-6 | 已缓解 |
| R-019 | Mock 数据看起来像真实当前行情，误导用户 | 中 | 严重 | 页面或 PDF 缺 Demo 标记；asOfDate 使用当天 | 每条 Mock 快照 isDemoData=true；页面、报告、导出永久水印；E2E 检查 | Phase 3 | 已缓解 |
| R-020 | LLM 成本并发超预算 | 中 | 高 | 多个步骤同时调用；工具循环被少算；最终费用超过任务预算 | 按多轮最坏上界估算；锁 Research 的事务式成本/调用数预留；按实际 usage/HTTP 次数结算；版本化价格 | Phase 6 | Phase 6 已缓解；生产价格需持续更新 |
| R-021 | 验证器只能检查 ID，无法检查自由文本数字和语义支持关系 | 高 | 严重 | Evidence ID 存在但 Claim 数字或方向错误 | 结构化 numericReferences；确定性数值/日期验证；语义验证仅作补充；不合格 Claim 删除 | Phase 5 | 已缓解 |
| R-022 | SEC HTML 清洗和章节切分失败，检索遗漏重要内容 | 中 | 高 | Risk Factors/MD&A 无块；导航和表格文本丢失 | 保留原文哈希；多策略标题识别；解析覆盖率指标；代表性 Filing fixture | Phase 5、7 | Phase 5 解析/检索基础已缓解；真实 Filing 评测开放 |
| R-023 | PDF 在 Docker 中中文缺字、分页破碎或图表缺失 | 高 | 中 | 本地正常、容器 PDF 空白或乱码 | 固定 OpenHTMLtoPDF；内置 Noto Sans CJK；渲染快照测试；PDF 失败不阻断 HTML | Phase 3、9 | Phase 3 已缓解；Phase 9 监控 |
| R-024 | 缓存键不完整导致不同 Provider、时间范围或 Schema 互相污染 | 中 | 高 | 报告读到另一 Provider 数据；升级后旧缓存异常 | Key 包含 provider、symbol、type、range、interval、schemaVersion；Cache Key 单测 | Phase 3、7 | 开放 |
| R-025 | 缓存和数据库数据无法复现旧报告 | 中 | 高 | 历史报告重新打开后数字变化 | 报告绑定 Source Snapshot、Evidence 和 calculationVersion；不动态重算旧版本 | Phase 3、5 | 已缓解 |
| R-026 | 删除研究记录破坏审计链或留下孤儿数据 | 中 | 中 | Evidence/Report 外键丢失；删除后费用记录不可追踪 | 默认软删除；定义保留期；外键和级联策略测试 | Phase 2、9 | 缓解中 |
| R-027 | Testcontainers、Playwright、Compose smoke 在 CI 中不稳定 | 中 | 高 | 偶发超时；共享端口；依赖真实时间 | 固定镜像和种子；动态端口；健康等待；分层 CI；保留失败日志和产物 | Phase 1-9 | 开放 |
| R-028 | scikit-learn、scipy 等未使用依赖增加镜像和供应链面 | 中 | 中 | 大镜像、安装慢、CVE 告警 | 只安装实际使用依赖；锁版本；定期扫描；不因原始建议而强制空依赖 | Phase 1、9 | 开放 |
| R-029 | Data Quality 或 Confidence 由 LLM 随意给分，产生虚假精确感 | 高 | 高 | 相同 Evidence 多次运行得分变化 | 使用 confidence_v1 与 data_quality_v1 确定性公式；记录各分项 | Phase 3、5 | 已缓解 |
| R-030 | 部分完成报告仍包含验证失败的事实 | 中 | 严重 | PARTIALLY_COMPLETED 页面显示 unsupported Claim | 不安全 Claim 必须隔离；模型结果沿用完整 Validator 与最多一次确定性修复；可安全删减才发布 partial，否则 FAILED | Phase 5-6 | Phase 6 已缓解 |
| R-031 | 实时“当前”概念与美股交易日、时区、延迟数据不一致 | 中 | 高 | 周末被标 stale；盘中使用未完成日线 | 交易日历和 Provider 数据延迟元数据；报告显示 asOfDate/generatedAt；UTC 存储 | Phase 4、7 | 开放 |
| R-032 | 导出 HTML 存在 XSS，PDF 渲染器访问外部资源导致 SSRF | 中 | 严重 | HTML 包含 script；渲染器请求任意 URL | 严格转义和 CSP；禁用远程资源；图表/字体本地内嵌；URL 白名单 | Phase 3、9 | Phase 3 已缓解；Phase 9 开放 |

### 2.1 Gate G2 风险处置（2026-07-10）

远端证据：[GitHub Actions run 29076405369](https://github.com/wubokai/AI-reserch/actions/runs/29076405369)，其中完整 API Testcontainers、并发/恢复、真实 HTTP 与五服务 Compose smoke 全部通过。

| 风险 | Gate G2 处置与证据 | 剩余风险 |
|---|---|---|
| R-005 | PostgreSQL durable queue、lease/heartbeat、reaper、20 Worker 竞争与旧 token fencing 已通过真实数据库测试 | 无 Phase 2 残余；架构变更时重新评审 |
| R-006 | Step input/implementation hash、attempt 唯一约束、checkpoint、fencing、幂等重放及成功输出复用已验证 | Phase 6 仍需 LLM 调用去重和费用预留，因此保持“缓解中” |
| R-012 | 所有 Research 读写均为 owner-scoped，真实 Basic Auth HTTP IDOR 用例通过 | Phase 9 新增 Report/Evidence/Export 资源时必须复用所有权边界，因此保持“缓解中” |
| R-026 | Research 默认软删除、并发行锁、审计/outbox 保留及外键顺序清理已验证 | Phase 9 仍需定义 Report/Evidence/费用记录保留期，因此保持“缓解中” |

### 2.2 Gate G3 风险处置（2026-07-10）

本地自动化与真实 Java→Python→PostgreSQL/Web BFF 链路、以及 [GitHub Actions run 29107016327](https://github.com/wubokai/AI-reserch/actions/runs/29107016327) 的 Web、Analytics、API/Testcontainers、secret scan 和 Compose 全部通过。Compose 复现了镜像构建、五服务与 Web 聚合健康、幂等重放、3 次轮询后 `COMPLETED`、Evidence、已验证报告 v1、历史、三种确定性导出和 Web BFF 历史/PDF。详细场景见 [`phase3-test-matrix.md`](./phase3-test-matrix.md)。

| 风险 | Phase 3 已实现的控制与验证证据 | 剩余风险/下一 Gate |
|---|---|---|
| R-001 | MU/NVDA/RKLB 已能从创建运行到已验证报告、Evidence、情景、导出和历史重开；Playwright 与远程 Compose 闭环通过 | Phase 0–3 范围风险已缓解；范围重新扩张时重评 |
| R-002 | Phase 3 不调用真实 LLM；所有数字来自确定性 Provider/Analytics，Claim 的 Evidence/数值/单位/日期由发布前验证器检查 | Phase 6 接入真实 LLM 时重新开放模型伪造风险 |
| R-003 | 报告主要叙事、Bull/Bear、催化剂、风险、情景总结和结论都以结构化 Claim 渲染；material Claim 无 Evidence 时失败关闭 | Phase 5 仍需扩展更完整的语义支持和受约束修复 |
| R-006 | 实际故障演练中，Analytics 恢复后从 `RUN_QUANT_ANALYSIS` 续跑，早期成功步骤未重复执行；报告/manifest 只发布一次 | Phase 6 仍需 LLM 调用去重和费用预留/结算 |
| R-009 | Java Analytics 客户端、Python Pydantic Schema、Web Zod/BFF 和 Artifact API 都有契约测试；远程三语言与 Compose 组合通过，BFF 导出字节/响应头一致 | 后续契约变更仍必须同步 OpenAPI/Schema 与三语言测试 |
| R-019 | 所有 Mock 快照/Evidence 带 Demo 标志；页面、JSON 报告和 Markdown/HTML/PDF 均显示 `DEMO DATA - NOT REAL MARKET DATA`；本地 E2E/PDF 与远程导出闭环通过 | 新增展示/导出表面时继续要求 Demo 标记回归 |
| R-023 | PDF 使用内置 Noto Sans SC；中文 PDF 文本抽取和逐页视觉 QA 通过，远程容器的确定性 PDF 导出通过 | Phase 9 仍需长文、图表与极端分页回归 |
| R-025 | 报告绑定不可变 Source Snapshot、Evidence、Quant Result、calculation version 和内容哈希；历史按版本读取，重复导出字节一致 | Phase 5 仍需对真实来源修订、长期保留和更多报告版本做回归 |
| R-029 | Phase 3 使用版本化确定性 Data Quality/Confidence 逻辑，不允许 Mock 生成器自由评分；相同输入报告哈希稳定 | Phase 5 需完善各分项解释和与 Freshness/来源冲突的系统评测 |
| R-032 | HTML 转义恶意 script/link fixture，输出无 `<script>`、外部 `href/src/url`；PDF 只使用本地字体/资源；远程 HTML/PDF 导出通过 | Phase 9 在引入新图表/资源时重新评审 CSP/SSRF |

### 2.3 Gate G4 风险处置（2026-07-10）

[GitHub Actions run 29111976669](https://github.com/wubokai/AI-reserch/actions/runs/29111976669)
的 Web/Playwright、Analytics、API/Testcontainers、secret scan 和 Compose 全部通过。首次远程 run
`29111664939` 暴露了可选指标缺失被报告层误判为 partial 的真实回归；修复后新增 policy/report
回归测试并重新完成五服务闭环。详细证据见 [`phase4-test-matrix.md`](./phase4-test-matrix.md)。

| 风险 | Phase 4 已实现的控制与验证证据 | 剩余风险/下一 Gate |
|---|---|---|
| R-004 | 73 个 Metric 的公式、阈值、符号、舍入、状态和顺序固化在 `quant_v1`；市场/财务黄金集、手算样例、性质测试与 59/60、99/100、199/200 边界通过 | 新 calculationVersion 或公式变更时必须重新建立黄金基线 |
| R-009 | Pydantic/JSON Schema 新增 Trend；Java 校验根/Metric/Trend 版本和结构；可选缺失与报告必需缺失的降级策略分别有 consumer test | Phase 5 新增 Evidence 契约时继续同步机器 Schema 和多语言测试 |
| R-013 | ETF 的 16 项公司基本面和 9 项公司估值统一返回 `NOT_APPLICABLE` + `ETF_NOT_APPLICABLE` | Phase 5 仍需在报告模板/SEC 步骤贯彻能力矩阵 |
| R-015 | adjustedClose 收益真源、ATR 同比缩放 OHLC、共同日期 inner join、错位 warning 和乱序稳定性已验证 | Phase 7 真实 Provider 仍需公司行动/分红/交易日历 contract test |
| R-016 | 财务期间、单位、TTM/年度/季度选择、CapEx 符号、EPS 跨零、税率/权益/EBITDA 边界和人工多期黄金样例已验证 | Phase 7 真实 Provider/XBRL concept 映射仍需人工黄金公司样本 |
| R-017 | 无可追踪预测时 Forward P/E 明确 `FORECAST_DATA_UNAVAILABLE`；可选缺失保留 limitation 但不误判整个报告 partial | Phase 7 只有经许可且带 lineage 的预期数据才能启用 |

### 2.4 Gate G6 风险处置（2026-07-10）

[GitHub Actions run 29118462224](https://github.com/wubokai/AI-reserch/actions/runs/29118462224)
的 Web/Playwright、Analytics、167 个 Surefire、44 个 Failsafe/Testcontainers、secret scan
和五服务 Compose 全部通过。详细证据见 [`phase6-test-matrix.md`](./phase6-test-matrix.md)。

| 风险 | Phase 6 已实现的控制与验证证据 | 剩余风险/下一 Gate |
|---|---|---|
| R-002 | Final Report 使用严格 Schema；Evidence/Calculation ID 受当前 Research allowlist 约束；模型输出继续经过数字、日期、类型和发布 Validator | 生产模型或 Schema 版本变化时重跑质量/对抗集 |
| R-006 | 请求哈希、attempt 唯一键、预算 reservation 唯一键、成功/失败追加审计和报告原子提交已覆盖重放 | Provider 是否实际计费仍以其账单为外部事实，需生产对账 |
| R-010 | 外部文本标记为不可信数据；系统指令分离；只允许三个 research-scoped 只读工具；禁并行工具调用且限制轮次/输出 | Phase 7 真实 SEC 文本接入后扩大对抗 fixture |
| R-020 | Research 行锁下同时预留最坏成本与最多 HTTP 次数；多轮上下文/工具输出计入上界；实际 usage/调用数结算；失败先审计再释放预留 | 生产价格版本需在生效日更新；模型计价变化时失败关闭 |
| R-030 | 真实模型失败只进入显式安全回退；回退与模型候选都复用完整 Validator 和最多一次确定性修复，不安全内容不发布 | 若未来启用模型修复，必须新版本并重新开 Gate |

## 3. MVP 接受的受控限制

以下不是遗漏，而是为了尽快形成安全纵向闭环而接受的限制：

| 限制 | 控制措施 | 解除阶段 |
|---|---|---|
| 只使用 Mock 数据 | 全局 Demo 水印、固定数据、禁止描述为实时 | Phase 7 |
| 只支持 MU/NVDA/RKLB 目标与 SPY/QQQ 基准 | 创建边界失败关闭；不把基准当目标，不对其他 symbol 伪造覆盖 | Phase 7 |
| MIXED_TEST 只用于自动化集成测试 | 禁止用户任务选择该模式，禁止生成用户可见报告或导出 | 始终保持 |
| 只有 STANDARD 深度 | UI 不提供无效选项；Schema 保留扩展能力 | 完整 v1 |
| 只有 5y 日线 | period 明确校验；不假装支持任意范围 | 完整 v1 |
| 技术分析必须启用 | UI 锁定必选；API 对 false 返回 `INVALID_REQUEST`，保持 Phase 3 full-analysis 契约唯一 | 完整 v1 |
| 基本面/宏观开关是受控降级而非任意 DAG | 基本面 false 只跳过叙事，情景数据仍获取并发布带 warning 的安全 partial；宏观 false 只跳过宏观取数 | Phase 8 |
| 无真实登录页 | dev-demo profile；仍检查资源所有权 | 后续 Should |
| 无新闻 Provider | Catalyst 仅来自已注册 SEC/宏观 Evidence | Could |
| 无向量数据库 | PostgreSQL 全文搜索接口先行 | Could |
| Provider 页面只读 | 运行配置通过环境变量 | Could |
| 不支持 Forward P/E 和同行估值 | 返回 NOT_AVAILABLE 并说明数据缺口 | Phase 7 后按许可决定 |

## 4. Phase 7 Provider 与许可决策

### 4.1 当前明确决定

- SEC EDGAR：计划使用官方公开接口，作为公司 Filing 主来源。
- FRED：计划使用官方 API，作为宏观数据来源。
- Market Data Provider：Twelve Data 为商务候选，但公开条款不覆盖本项目外部展示与导出；
  在取得 Redistribution Rights Add-On 或单独书面协议前保持未选择/禁用。
- Fundamental Data Provider：选择 SEC EDGAR Companyfacts/XBRL；实现须通过 concept、期间、
  单位、正负号和人工黄金样例 Gate。
- News Provider：不属于 v1 Must。
- Analyst Estimates Provider：不属于 v1 Must。

“尚未选择”是有意的架构约束。此前阶段只能依赖领域接口和 Mock，不得在 Controller、Service、Entity 或报告 Schema 中嵌入供应商专有字段。

详细决策与逐项权利见 [`adr/0009-phase7-provider-license-decision.md`](./adr/0009-phase7-provider-license-decision.md)
和 [`provider-license-matrix.md`](./provider-license-matrix.md)。

### 4.2 许可评审清单

任何真实行情或基本面 Provider 在接入前必须回答：

1. 是否允许服务器端缓存，最大缓存期限是多少。
2. 是否允许把数据持久化到 PostgreSQL。
3. 是否允许向终端用户展示原始值和派生指标。
4. 是否允许在 Markdown、HTML、PDF 中导出或再分发。
5. 是否要求显示品牌、版权、延迟或来源声明。
6. 是否允许保存历史快照以复现旧报告。
7. 是否允许从原始数据计算并展示派生指标。
8. 行情是否含拆股、分红和 adjusted close。
9. 历史深度、速率、并发、日配额、超额费用和 SLA。
10. ETF、类别股、退市证券和代码变更覆盖情况。
11. 财务数据修订、币种、单位、财年和 XBRL 映射方式。
12. 是否提供 Forward P/E 所需的一致预期；若没有，系统必须保持 NOT_AVAILABLE。

### 4.3 Stop 条件

满足以下任一条件时不得接入：

- 条款禁止本项目必需的持久化或报告导出。
- 无法明确识别数据延迟或有效日期。
- 不提供公司行动调整信息却声称支持总收益分析。
- 许可证、费用或配额无法覆盖预期演示和测试。
- 要求把 API Key 暴露给前端。
- 响应无法通过 Adapter 标准化而必须污染领域模型。

## 5. 风险评审节奏

- 每个 Phase 开始时复核责任阶段对应的开放风险。
- 每个 Phase Gate 前关闭、接受或明确延期所有严重/高风险。
- Provider 接入、LLM 模型变更、量化公式变更、报告 Schema 变更时必须重新评审。
- 已关闭风险若触发信号再次出现，应恢复为开放状态。
- 风险状态变化应在 Pull Request 或阶段交付说明中记录原因、证据和剩余风险。
