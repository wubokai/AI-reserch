# 数据源、来源追踪与新鲜度

## 1. 原则

- 领域层依赖 Provider 端口，不依赖第三方 SDK、URL 结构或原始响应对象。
- 每次真实或 Mock 获取都生成不可变 `SourceSnapshot`。Evidence 只能引用已持久化的快照或确定性计算结果。
- `publishedAt` 表示来源发布日期，`effectiveDate` 表示数据代表的业务日期，`retrievedAt` 表示系统抓取时间，三者不得混用。
- 系统时间以 UTC 保存。交易日判断使用证券交易所日历，而不是简单的工作日计算。
- 缺 Key、限流或单一 Provider 故障不得伪造回退数据。只有显式 `DATA_MODE=mock` 才能使用 Mock 数据。
- Mock 与真实数据禁止在同一研究任务中静默混合。若测试场景需要混合，报告必须以 `MIXED_TEST` 标识且不可作为普通用户报告发布。

## 2. 数据模式

| 模式 | 用途 | 发布要求 |
| --- | --- | --- |
| `MOCK` | 本地开发、CI、演示 | 页面和所有导出持续显示 `DEMO DATA - NOT REAL MARKET DATA` |
| `REAL` | 已配置并通过健康检查的真实 Provider | 所有关键数据有来源、哈希、有效日期和许可记录 |
| `MIXED_TEST` | 仅限自动测试/故障演练 | API 明确返回测试标记，禁止普通 UI 发布 |

研究任务创建时固定 `dataMode`；任务执行中不得自动从 `REAL` 切换为 `MOCK`。真实来源不可用时使用 `PARTIALLY_COMPLETED`、结构化 warning 或失败状态。

## 3. Provider 端口

```text
SecurityMasterProvider
  resolveSecurity(query, exchangeHint?) -> SecurityProfile

MarketDataProvider
  getDailyBars(security, startDate, endDate) -> AdjustedOhlcvSeries

FundamentalDataProvider
  getStatements(security, periods, asOfDate) -> FinancialStatementSet
  getMetrics(security, asOfDate) -> FundamentalMetricSet

FilingProvider
  listFilings(security, filingTypes, asOfDate) -> FilingMetadata[]
  getFiling(accessionNumber) -> FilingDocument

MacroDataProvider
  getSeries(seriesId, startDate, endDate, asOfDate) -> MacroSeries

NewsProvider (optional, disabled in MVP)
  searchCompanyNews(security, from, to) -> NewsArticle[]
```

所有端口返回统一封装：

```text
ProviderResult<T> {
  data: T | null
  snapshot: SourceSnapshot
  warnings: ProviderWarning[]
  completeness: COMPLETE | PARTIAL | EMPTY
}
```

Adapter 负责认证、分页、速率限制、字段映射、响应校验和来源 URL 构造；领域层负责是否接受、合并或降级。

## 4. 第一版来源路线

| 数据 | MVP | 完整 v1 | 备注 |
| --- | --- | --- | --- |
| 证券、行情、基本面、Filing、宏观 | 确定性 Mock Provider | 可替换真实 Adapter | Mock 固定种子与固定 `asOfDate` |
| SEC Filing | Mock 文档 | SEC EDGAR（Phase 7 首检查点已接入，默认关闭） | 主要来源；合规 User-Agent、限流、HTML 清理、原始哈希与来源 URL 已实现 |
| 宏观 | Mock 序列 | FRED（Phase 7 本地检查点已接入） | 保留频率、单位、realtime vintage、修订边界、effective date 与归属声明 |
| 行情 | Mock adjusted OHLCV | 供应商待 Phase 7 许可评审 | 必须支持历史保存、展示和导出的合同权利 |
| 基本面 | Mock 报表 | SEC EDGAR Companyfacts/XBRL（Adapter 已实现） | 官方主来源；保留 taxonomy/concept/unit/period/accession，并通过修订/跨期黄金样例 |
| 新闻 | 不启用 | 可选 | 不作为报告完成的硬依赖，不抓取付费墙 |

行情和基本面供应商暂不在 Phase 0 硬编码。Phase 7 选型门禁包括：覆盖率、调整价口径、财务字段定义、历史深度、限流、SLA、成本，以及存储、缓存、派生分析、UI 展示和 PDF 导出的许可。

Phase 7 许可结论：Market 尚未获得覆盖外部展示和报告导出的书面权利，保持 Mock；
Fundamental 选择 SEC Companyfacts/XBRL。详见[许可矩阵](./provider-license-matrix.md)。

SEC XBRL 的标准 concept、期间选择、修订去重、派生公式与 NOT_AVAILABLE 规则见
[Companyfacts 映射与数据质量规则](./sec-xbrl-mapping.md)。

### 4.1 SEC EDGAR Adapter v1

- 证券代码先通过 SEC 官方 `company_tickers.json` 映射 CIK，再读取 `data.sec.gov/submissions/CIK##########.json`，只下载允许的 10-K、10-Q、8-K、20-F、40-F、6-K 及修订版主文档；
- `SEC_USER_AGENT` 必须包含应用身份与受监控联系邮箱。实现上限为 1–10 次/秒，默认 8 次/秒，遵守 SEC 当前不超过 10 次/秒的自动访问政策；
- 生产 URL 只允许 `https://data.sec.gov` 与 `https://www.sec.gov`，拒绝 user-info、非 HTTPS 官方地址、路径穿越和非允许文档标识；Contract Test 可使用 loopback HTTP；
- JSON/HTML 响应分别验证内容类型、空响应和字节上限。只对 429、502、503、网络错误和超时进行有界重试，不对 4xx、Schema、路径或内容类型错误重试；
- `rawDataHash` 对 ticker 元数据、submissions JSON 和下载文档的原始字节按长度分隔后计算 SHA-256；规范化 Payload 另算 `normalizedDataHash`；
- Source Snapshot 保存 provider、schema、retrieved/effective date、官方来源 URL、freshness、原始哈希和访问政策版本；每份 Filing 的官方 URL 保存为 `raw_text_uri`；
- 官方契约依据：[SEC EDGAR API](https://www.sec.gov/search-filings/edgar-application-programming-interfaces)、[SEC Webmaster FAQ](https://www.sec.gov/about/webmaster-frequently-asked-questions) 与 [SEC Rate Control Notice](https://www.sec.gov/filergroup/announcements-old/new-rate-control-limits)。

当前限制：尚未实现 SEC Redis 缓存、熔断器、Provider 状态指标与完整 REAL 研究编排，因此本检查点不关闭 Gate G7。

### 4.2 FRED Adapter v1

- 每个配置序列先读取 `/fred/series` 元数据，再读取 `/fred/series/observations`；请求固定 `realtime_start=realtime_end=vintageDate`，避免把未来修订混入历史快照；
- 保存 frequency、units、seasonal adjustment、last updated，以及每个 observation 的 realtime start/end；官方缺失值 `.` 明确跳过，截断、空序列和非法数字快速失败；
- API key 只来自环境变量和请求参数，不进入 Source Snapshot、source URL、异常 message/cause 或日志；正式地址只允许 `https://api.stlouisfed.org`；
- 原始 metadata/observation 字节形成 SHA-256，规范化 Payload 另存哈希；Source Snapshot 保存 vintage、effective date、freshness、许可复核版本和官方要求的归属文本；
- 契约依据：[FRED Series Observations](https://fred.stlouisfed.org/docs/api/fred/series_observations.html)、[FRED API Terms](https://fred.stlouisfed.org/docs/api/terms_of_use.html) 与 [FRED Legal Terms](https://fred.stlouisfed.org/legal/terms/)。

FRED 缓存、熔断、Provider 指标及页面/Markdown/HTML/PDF 归属展示均已完成；归属文本和许可策略版本来自不可变 Source Snapshot，导出模板版本变化会强制生成新缓存。完整 REAL 闭环仍受 Market 书面权利阻塞。

## 5. SourceSnapshot

每次获取保存：

```text
id
provider
providerDataset
sourceType
sourceUrl
publishedAt
effectiveDate
retrievedAt
requestFingerprint
rawDataHash (SHA-256)
contentType
schemaVersion
isPrimarySource
freshnessStatus
licensePolicyVersion
storageLocator
```

- `requestFingerprint` 是规范化请求的哈希，不含密钥。
- `rawDataHash` 基于原始字节；规范化数据另存 `normalizedDataHash`。
- 大型 Filing 原文可迁移到对象存储，但数据库始终保留哈希、定位器和元数据。
- URL 可能变化，Evidence 的真实性依赖快照哈希与定位器，而不只依赖 URL。

## 6. 新鲜度规则 v1

新鲜度以研究任务的 `asOfDate` 评估，而不是以浏览页面的当前时间评估。阈值存放在版本化配置中，报告记录所用 `freshnessPolicyVersion`。

| 数据类型 | `FRESH` | `STALE` | `VERY_STALE` | `UNKNOWN` |
| --- | --- | --- | --- | --- |
| 日线行情 | 最新应有交易日距 `asOfDate` 0-2 个交易日 | 3-5 个交易日 | 超过 5 个交易日 | 缺交易所日历或有效日期 |
| 证券资料 | 30 天内验证，且证券仍 active | 31-90 天 | 超过 90 天 | 无验证时间 |
| 公司财务/SEC 覆盖 | 最近 10-Q/10-K 在 120 天内且无已知缺档 | 121-180 天 | 超过 180 天 | 无 CIK、无 Filing 日期或证券不适用 |
| 宏观序列 | 最新观测落在 `2 x 频率 + 官方发布滞后` 内 | 到 `3 x 频率 + 滞后` | 更久 | 未知频率/发布滞后 |
| 新闻/事件（启用后） | 研究窗口内且 7 天内抓取 | 8-30 天 | 超过 30 天 | 无发布时间 |

补充规则：

- Filing 本身是不可变历史事实；表中“新鲜度”描述的是覆盖是否更新到应有报告期。
- ETF 的公司财务和 10-K 可为 `NOT_APPLICABLE`，不计入缺失或新鲜度分母。
- Mock 快照相对于固定 fixture `asOfDate` 评估，并额外带 `dataMode=MOCK`；不得用系统日期把演示数据描述为实时。
- `UNKNOWN` 必须降低数据质量分数并产生 warning，不能视同 `FRESH`。

## 7. 缓存

缓存 Key 规范：

```text
{dataType}:v{schemaVersion}:{provider}:{securityKey}:{intervalOrPeriod}:{start}:{end}:{asOf}
```

默认 TTL（可配置）：

| 数据 | TTL |
| --- | --- |
| 证券资料 | 24 小时 |
| 已结束交易日的日线行情 | 24 小时；历史区段可 7 天 |
| 财务报表 | 24 小时 |
| SEC 元数据 | 6 小时；已下载不可变文档按哈希长期复用 |
| 宏观序列 | 6 小时 |
| 研究中间结果 | 任务完成后 24 小时 |

缓存失效只影响性能；数据库中的快照与研究步骤仍是恢复依据。不得把 Redis 命中当作 Evidence，命中内容仍须解析为已注册快照。

Phase 7 Provider Runtime 实现细则：

- SEC Filing、SEC XBRL 与 FRED 规范快照使用统一 Redis JSON 缓存，默认 TTL 6 小时；
- Key 为 `provider:v1:{provider}:{schemaVersion}:{sha256(subject)}`，不保存 symbol 以外的原始请求内容、API Key 或查询参数；
- 单项默认最多 5 MB，超大 Filing 快照跳过缓存但仍正常返回并落 PostgreSQL；
- Redis 读写失败只记录 `provider.cache{outcome="error"}` 并按 miss 继续，不把缓存变成真实数据可用性的单点故障；
- 缓存命中仍通过后续 Source Snapshot/Evidence 注册，不直接成为 Evidence。

统一熔断只统计 `retryable=true` 的 `ProviderAccessException`。Schema、许可、无数据、非法路径等
永久错误不计入 failure rate。Prometheus 暴露 `provider.requests`、`provider.cache`、
`provider.retries`，tag 仅包含受控 provider/outcome/reason，不包含 symbol、URL 或密钥。

## 8. 合并、冲突与缺失

- 对同一事实的多来源值不静默择优。保存每个快照并生成 `SOURCE_CONFLICT`。
- 主要来源优先用于 FACT；二级来源可补充但必须标记。
- 金额比较前统一币种、单位和财务期间，不做隐式换算。
- Provider 返回空列表与调用失败是不同状态：`EMPTY` 可以是合法结果，失败必须带错误类别。
- Forward P/E、同行估值、未来事件日历在来源不支持时返回 `NOT_AVAILABLE`，不得由 LLM 补齐。

## 9. Contract Test 最低要求

每个真实 Adapter 上线前必须验证：

1. 正常响应、空响应、分页和字段缺失；
2. 401/403、429、5xx、超时和错误内容类型；
3. 来源 URL、时间字段、单位与哈希正确；
4. Rate Limit、Retry、Circuit Breaker 和缓存行为；
5. 不在日志、缓存 Key 或异常中泄露密钥；
6. 供应商 Schema 变化时快速失败并产生可诊断错误；
7. 存储、缓存、展示、派生与导出行为符合许可。
