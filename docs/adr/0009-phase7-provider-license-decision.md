# ADR-0009：Phase 7 行情与基本面来源许可决策

- 状态：接受
- 日期：2026-07-10

## 背景

真实研究必须持久化原始快照、生成派生指标、在 Web 展示，并导出 Markdown、HTML 和
PDF。普通 API 访问权不等于这些存储、展示和再分发权。若公开条款未明确覆盖全部行为，
系统不得把 API Key 可用误判为许可通过。

## 决策

1. Fundamental v1 选择 SEC EDGAR Companyfacts/XBRL。它是 Filing 同一官方主来源，
   无 API Key，沿用 SEC 声明式 User-Agent、10 次/秒上限和来源追踪。实现必须保留 taxonomy、
   concept、unit、period、filed date 与 accession，并使用版本化标准概念映射和人工黄金样例。
2. Market 暂不选择并保持 `mock`。Twelve Data 进入首选商务候选，但公开订阅仅授予内部
   使用；第三方展示或再分发需要 Redistribution Rights Add-On 或单独书面协议。本项目在
   获得覆盖 PostgreSQL 持久化、Redis 缓存、派生计算、Web 展示和三种导出的书面权利前停止接入。
3. 不使用 `yfinance`、Yahoo 非官方接口或页面抓取作为生产 Provider，因为它们不能提供
   本项目所需的稳定合同、Schema、SLA 和再分发授权证据。
4. 真实 Market 配置必须同时提供 `MARKET_DATA_LICENSE_CONFIRMED=true` 与非空版本化
   `MARKET_DATA_LICENSE_POLICY_VERSION`。缺任一项时应用失败关闭；仅有 API Key 不足以启用。

## 依据

- SEC 官方 API 明确提供 Companyfacts、Companyconcept 和 Frames JSON，并说明 XBRL 数据
  来自 10-Q、10-K 等 Filing：[SEC EDGAR APIs](https://www.sec.gov/search-filings/edgar-application-programming-interfaces)。
- Twelve Data 公开条款将默认许可限定为 Internal Use；外部展示/再分发仅在订阅层、
  Redistribution Rights Add-On 或单独协议明确允许时成立：
  [Twelve Data Terms](https://twelvedata.com/terms)。
- Twelve Data 公开定价对内部 non-display 与内部 display 权利分别描述，并明确内部数据
  不得向外部方提供：[Twelve Data Pricing](https://twelvedata.com/pricing)。

## 后果

- Fundamental 可继续实现，不依赖新增商业合同，但仍需解决 XBRL concept/期间/单位风险。
- Market 相关真实收益、Beta、技术指标保持不可发布；Mock 流程继续完整运行。
- 用户取得书面 Market 数据协议后，必须把合同/计划标识、复核日期、缓存期限、归属要求
  和允许的输出渠道写入新的 policy version，再实现和启用 Adapter。
