# 项目进度

最后更新：2026-07-11

| Phase | 状态 | 结果 |
| --- | --- | --- |
| Phase 0：需求与架构 | 完成 | 需求、ADR、架构/数据流/状态机/ER、OpenAPI、Analytics/LLM Schema、方法论、安全、风险和计划已固化 |
| Phase 1：项目骨架 | 完成 | Web、API、Analytics、Compose、Health 和 CI 已完成；远端 Docker build、五服务启动与 smoke 通过，Gate G1 通过 |
| Phase 2：持久任务与状态机 | 完成 | Flyway V1–V4、Research API、durable queue、lease/fencing/reaper、状态机、幂等、重试/取消/软删除、所有权、审计/outbox 与健康降级已实现；Gate G2 通过 |
| Phase 3：Mock 纵向闭环 | 完成 | MU/NVDA/RKLB 固定 Mock Provider、SPY/QQQ 基准、确定性量化、Evidence/Claim、报告验证/原子发布、历史、情景和三种导出已通过本地与远程闭环验证；Gate G3 通过 |
| Phase 4：完整量化服务 | 完成 | 73 个有序 Metric、确定性 Trend、黄金数据集、完整数值/语义边界、Java/Python 契约和原有 Mock 闭环回归均通过；Gate G4 通过 |
| Phase 5：Evidence 与 SEC 检索基础 | 完成 | Source Snapshot、日期/数值引用、确定性 Freshness/Confidence/Data Quality、一次安全修复、Filing 清洗/Chunk/GIN 检索、Evidence Drawer 与快照复现均通过；Gate G5 通过 |
| Phase 6：真实 LLM 安全接入 | 完成 | `ResearchLanguageModel` Mock/Real 双实现、Responses API、6 个严格 Schema、三工具 allowlist、预算预留/结算、调用/失败审计和安全回退已通过；Gate G6 通过 |
| Phase 7：真实数据源 | 进行中 | SEC/FRED/XBRL、Runtime 与多格式归属已通过 CI；provider-neutral REAL 创建/解析/发布边界已完成本地检查点；Market 许可和 Adapter 仍阻塞完整 REAL 终验 |
| Phase 8：完整前端产品 | 完成 | Dashboard、完整表单、进度控制、报告图表、Evidence/Data Quality、版本/历史、Provider 状态、导出反馈、响应校验、移动端均完成；Gate G8 通过 |
| Phase 9：发布硬化 | 完成 | JSON 日志、Prometheus/SLO、输入/SSRF/XSS/IDOR/PDF、全局执行截止时间、供应链、最小权限容器、运行/扩展/保留文档和远端 G9 工程 Gate 均完成 |

## 已验证

- 原始 DOCX 需求已全文读取并完成结构/页面核对；
- OpenAPI 3.1 YAML 可解析，本地引用与必需操作完整；
- LLM/Analytics JSON Schema 是合法 JSON；
- Web：ESLint、TypeScript、32 个 Vitest、Next.js production build 与 5 个 Playwright 用例通过；Phase 8 状态、操作、版本、筛选、导出、Zod、company-only、周期/深度、Provider 和移动端矩阵已覆盖；
- API：Java 21 / Spring Boot 3.5，231 个 Surefire 本地通过，49 个 Failsafe/Testcontainers 已定义（新增 V9/FTS/血缘覆盖待本提交远端执行）；新增 Phase 9 日志/指标、JSON/PDF 边界、SSRF、安全头、company-only、1y/3y/5y、QUICK/STANDARD/DEEP、完整安全报告章节、Research 全局执行截止时间、outbox relay 与 LLM Filing FTS 覆盖；
- PostgreSQL 17：Flyway V1–V9；V8 提供 LLM 原子预算/审计，V9 提供带 Research/Source 血缘、唯一约束和不可变触发器的 `market_price_bars`、`financial_metrics`、`macro_series` 规范化事实投影；
- Analytics：Ruff、strict mypy、41 个 pytest 通过，branch coverage 93.92%；完整覆盖收益、风险、技术/Trend、基本面、估值与情景；
- 供应链：`pnpm audit` 与 Python `pip-audit --local` 均为 0 已知漏洞；PostCSS 已提升到修复版本，Dependabot 覆盖 npm/pip/Maven/Actions；
- 本地真实服务链路：MU、NVDA、RKLB 完整研究均形成已验证报告；关闭基本面叙事时安全部分完成，关闭宏观时完整完成；所有保留的重要 Claim 都关联同任务 Evidence；
- 故障恢复：Analytics 不可用时任务在重试预算耗尽后失败，恢复服务后从 `RUN_QUANT_ANALYSIS` 续跑，早期成功步骤未重复执行，最终发布报告与 manifest；
- 导出：Markdown/HTML/PDF 响应类型、文件名、ETag、SHA-256、版本与数据模式已验证；重复 PDF 字节一致，中文 PDF 已完成逐页视觉检查和文本抽取检查，HTML 无脚本和外部资源；
- GitHub Actions：Gate G2 的 Web、API（含 27 个 Failsafe/Testcontainers 测试）、Analytics、secret scan、Docker Compose build/up/smoke/down 全部通过（run `29076405369`）；
- GitHub Actions：Gate G3 的 Web、Analytics、API/Testcontainers、secret scan 与 Compose 五服务闭环全部通过（[run `29107016327`](https://github.com/wubokai/AI-reserch/actions/runs/29107016327)）。Compose 证据包括镜像构建、五服务与 Web 聚合健康、幂等重放、3 次轮询后 `COMPLETED`、Evidence、验证通过的报告 v1、历史、Markdown/HTML/确定性 PDF 与 Web BFF 历史/PDF；
- GitHub Actions：Gate G4 的 Web/Playwright、Analytics、API/Testcontainers、secret scan 与 Compose 全部通过（[run `29111976669`](https://github.com/wubokai/AI-reserch/actions/runs/29111976669)）。Compose 在扩展 73 个 Metric 后仍于 3 次轮询到达 `COMPLETED`，并验证 Evidence、报告 v1、历史、三种导出与 Web BFF；
- GitHub Actions：Gate G5 的 Web/Playwright、Analytics、156+40 个 API/Testcontainers、secret scan 与 Compose 全部通过（[run `29115859586`](https://github.com/wubokai/AI-reserch/actions/runs/29115859586)）；新增 PostgreSQL 测试覆盖 V7、GIN 检索、Filing/Chunk 不可变和历史 Snapshot 复现；
- GitHub Actions：Gate G6 的 Web/Playwright、Analytics、167+44 个 API/Testcontainers、secret scan 与 Compose 全部通过（[run `29118462224`](https://github.com/wubokai/AI-reserch/actions/runs/29118462224)）；新增覆盖 V8、预算/调用数原子 ledger、成功/失败脱敏审计、Responses HTTP mock、结构化输出和安全回退；
- GitHub Actions：Phase 7 SEC 首检查点的 Web/Playwright、Analytics、175+45 个 API/Testcontainers、secret scan 与 Compose 全部通过（[run `29134112081`](https://github.com/wubokai/AI-reserch/actions/runs/29134112081)）；真实 SEC Source Snapshot、raw/normalized hash 分离与 Filing 官方 URL 落库已完成容器验证；
- GitHub Actions：Phase 7 FRED 检查点的 Web/Playwright、Analytics、179+46 个 API/Testcontainers、secret scan 与 Compose 全部通过（[run `29134411188`](https://github.com/wubokai/AI-reserch/actions/runs/29134411188)）；vintage、归属、Key 脱敏和真实政府数据来源落库已完成容器验证；
- GitHub Actions：Phase 7 Provider 许可检查点的 Web/Playwright、Analytics、181+46 个 API/Testcontainers、secret scan 与 Compose 全部通过（[run `29138819196`](https://github.com/wubokai/AI-reserch/actions/runs/29138819196)）；Market 书面权利失败关闭和默认 Mock 无 Key 路径均已验证；
- GitHub Actions：Phase 7 SEC XBRL 检查点的 Web/Playwright、Analytics、185+47 个 API/Testcontainers、secret scan 与 Compose 全部通过（[run `29141192029`](https://github.com/wubokai/AI-reserch/actions/runs/29141192029)）；黄金映射、concept/accession lineage、mixed-period freshness 与 Mock 默认路径均已验证；
- GitHub Actions：Phase 7 Provider Runtime 检查点的 Web/Playwright、Analytics、191+48 个 API/Testcontainers、secret scan 与 Compose 全部通过（[run `29142394155`](https://github.com/wubokai/AI-reserch/actions/runs/29142394155)）；真实 Redis 命中/TTL、有界缓存、故障降级、可重试熔断与低基数指标均已验证；
- GitHub Actions：Phase 7 来源归属检查点的 Web/Playwright、Analytics、193+48 个 API/Testcontainers、secret scan 与 Compose 全部通过（[run `29143064626`](https://github.com/wubokai/AI-reserch/actions/runs/29143064626)）；SEC/FRED 归属、许可策略、REAL/Mock 标识隔离和 Markdown/HTML/PDF 模板版本均已验证；
- GitHub Actions：Phase 8 Gate G8 的 Web/Playwright、Analytics、API/Testcontainers、secret scan 与 Compose 全部通过（[run `29144269701`](https://github.com/wubokai/AI-reserch/actions/runs/29144269701)）；完整前端状态、操作、图表、Evidence/Data Quality、版本、历史、Provider、导出与移动端闭环均已验证；
- GitHub Actions：Phase 9 G9 工程 Gate 的 Web/Playwright、Analytics、208 个 API Surefire 与 Testcontainers、依赖审计、secret scan、最小权限容器策略、三应用镜像 Grype 扫描及五服务闭环全部通过（[run `29145630809`](https://github.com/wubokai/AI-reserch/actions/runs/29145630809)）；本文件所在最终证据提交的同名 `ci` check 作为连续第二次主干验证；
- Phase 3–9 详细证据见 [`phase3-test-matrix.md`](./phase3-test-matrix.md)、[`phase4-test-matrix.md`](./phase4-test-matrix.md)、[`phase5-test-matrix.md`](./phase5-test-matrix.md)、[`phase6-test-matrix.md`](./phase6-test-matrix.md)、[`phase7-test-matrix.md`](./phase7-test-matrix.md)、[`phase8-test-matrix.md`](./phase8-test-matrix.md) 和 [`phase9-test-matrix.md`](./phase9-test-matrix.md)。

## 当前限制

- 本机没有 Docker；Gate G3–G6、Phase 7 各检查点、G8 与 G9 的容器、Testcontainers 和 Compose 终验均由 GitHub Actions Linux runner 完成。
- 普通用户闭环只允许 `dataMode=MOCK`；目标支持 MU/NVDA/RKLB，基准支持 SPY/QQQ，周期支持 1y/3y/5y 或最长五年的显式范围，深度支持 QUICK/STANDARD/DEEP；核心量化所需技术分析必须启用。
- 基本面开关只控制可选叙事分析步骤；情景计算所需的规范化基本面数据仍会获取。宏观开关关闭时跳过宏观取数。
- SEC Filing、SEC XBRL 与 FRED Adapter 已接入但默认关闭；缓存、熔断和 Provider 指标已完成，完整 REAL 编排仍受 Market 许可阻塞，不能描述为可发布的真实研究闭环。
- SEC/FRED 页面与 Markdown/HTML/PDF 归属已完成本地验证。Fundamental 已选择 SEC Companyfacts/XBRL；真实 Market 在取得覆盖持久化、外部展示和报告导出的书面权利前保持未选择。
- REAL 证券必须预先存在于非 Demo security master；当前尚无自动注册 Adapter，缺失或歧义会明确失败，不会使用 Mock fixture。
- 真实 OpenAI Adapter 已接入但未配置生产模型、Key 或价格；当前默认仍只发布确定性 Mock 报告，测试不访问真实外部模型。
