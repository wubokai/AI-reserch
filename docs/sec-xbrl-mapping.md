# SEC Companyfacts/XBRL 标准化与数据质量规则

最后更新：2026-07-11  
Mapping Version：`us_gaap_fundamentals_v2`

## 1. 数据集与 Grain

输入是单一 CIK 的 SEC Companyfacts JSON。规范输出 grain 为：

```text
(symbol, normalizedMetricName, periodEndDate, periodType, mappingVersion)
```

每个规范指标最多一行。直接指标保留 taxonomy、concept、unit、accession、filed date；
派生指标保留全部 component concepts。原始 Companyfacts 与 ticker 映射响应共同形成
`rawDataHash`，规范输出另算 `normalizedDataHash`。

## 2. Concept 映射

| 规范指标 | SEC concepts（按优先级） | 单位 | 期间 | 规则 |
| --- | --- | --- | --- | --- |
| Revenue | `RevenueFromContractWithCustomerExcludingAssessedTax`, `Revenues`, `SalesRevenueNet` | USD | FY | 首个有合法年度事实的 concept |
| Operating Income | `OperatingIncomeLoss` | USD | FY | 直接事实 |
| Net Income | `NetIncomeLoss`, `ProfitLoss` | USD | FY | 直接事实 |
| Diluted Shares | `WeightedAverageNumberOfDilutedSharesOutstanding` | shares | FY | 直接事实 |
| Gross Margin | `GrossProfit / Revenue` | ratio | FY | 两事实 start/end 必须一致，Revenue 非零 |
| Free Cash Flow | `NetCashProvidedByUsedInOperatingActivities - PaymentsToAcquirePropertyPlantAndEquipment` | USD | FY | 两事实 start/end 必须一致 |
| EBITDA | `OperatingIncomeLoss + DepreciationDepletionAndAmortization` | USD | FY | 确定性代理口径；两事实 start/end 必须一致 |
| Net Debt | Total Debt concept − `CashAndCashEquivalentsAtCarryingValue` | USD | point-in-time | 两事实 end 必须一致 |

Total Debt concept 优先级：`LongTermDebtAndFinanceLeaseObligations`、
`LongTermDebtAndCapitalLeaseObligations`、`LongTermDebt`。

## 3. 自动数据质量检查

| 维度 | 稳定规则 | 失败处理 | 风险级别 |
| --- | --- | --- | --- |
| Identity | Companyfacts CIK 必须与 ticker 映射一致 | 整个快照失败 | Critical |
| Completeness | 至少一个受支持指标 | `SEC_XBRL_NO_SUPPORTED_FACTS` | High |
| Uniqueness | 同 concept/period 取最新 filed fact | 旧 fact 保留在 raw，仅规范输出去重 | High |
| Time travel | `filed <= retrievalDate`、`end <= retrievalDate`、`filed >= end` | 未来或时间矛盾 context 排除 | Critical |
| Form | 年度只允许 10-K/10-K/A；instant 允许 10-K/Q 及修订 | 其他表单排除 | High |
| Annual duration | start/end 之间 300–400 天且 `fp=FY` | 排除 | High |
| Unit | USD 与 shares 精确匹配，不隐式换算 | 指标 NOT_AVAILABLE | Critical |
| Accession | `##########-##-######` | context 排除 | High |
| Numeric validity | `val` 必须是 JSON number | context 排除 | Critical |
| Cross-period | 派生组件 start/end 必须一致 | 派生指标 NOT_AVAILABLE | Critical |
| Mixed periods | FY 流量与季度 instant 可同时出现 | `MIXED_PERIODS_PRESENT`；freshness 按 FY coverage | Medium |

## 4. 黄金样例证据

[`companyfacts-golden-v1.json`](../apps/api/src/test/resources/provider/sec/companyfacts-golden-v1.json)
固定覆盖：

- 同一年度的 10-K 与更晚 10-K/A，Revenue 应从 1000 修订为 1100；
- 抓取日之后 filed 的 9999 必须被排除；
- Gross Margin = `440 / 1100 = 0.4`；
- FCF = `300 - 80 = 220`；
- EBITDA proxy = `200 + 50 = 250`；
- Net Debt = `500 - 200 = 300`；
- 另一个负向 fixture 验证不同期间的 Gross Profit 与 Revenue 不得组合。

## 5. 当前限制

- 当前只标准化 US-GAAP；IFRS 与公司自定义 extension 不自动映射。
- 当前流量指标使用最新完整 FY，不把三个季度与年度/季度事实拼成 TTM。
- EBITDA 是明确标注的确定性代理口径，不等同公司披露的 Adjusted EBITDA。
- Net Debt 当前只在 SEC 提供可识别的 total debt concept 时计算，不把缺失 current debt
  当作零。
- 场景假设仍为空；真实情景输入需要独立、版本化且可追踪的假设政策。
- 指标缺失产生 `METRIC_NOT_AVAILABLE:<name>`，不得由 LLM 补值。
