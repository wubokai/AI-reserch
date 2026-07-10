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
| SEC Filing | Mock 文档 | SEC EDGAR | 主要来源；需合规 User-Agent、限流与 HTML 清理 |
| 宏观 | Mock 序列 | FRED | 每个序列保留频率、单位、修订信息与 effective date |
| 行情 | Mock adjusted OHLCV | 供应商待 Phase 7 许可评审 | 必须支持历史保存、展示和导出的合同权利 |
| 基本面 | Mock 报表 | 供应商待 Phase 7 许可评审 | 必须说明原始/标准化口径与公司行动处理 |
| 新闻 | 不启用 | 可选 | 不作为报告完成的硬依赖，不抓取付费墙 |

行情和基本面供应商暂不在 Phase 0 硬编码。Phase 7 选型门禁包括：覆盖率、调整价口径、财务字段定义、历史深度、限流、SLA、成本，以及存储、缓存、派生分析、UI 展示和 PDF 导出的许可。

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
