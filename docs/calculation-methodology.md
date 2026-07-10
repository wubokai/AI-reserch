# 确定性计算方法 v1

## 1. 适用范围与版本

本文定义 `calculationVersion=quant_v1`。Python Analytics 是所有量化、技术、基本面和情景结果的唯一计算方；LLM 只能解释已注册结果。

约定：

- 内部向量计算使用 IEEE-754 `float64`；货币在 API 边界以十进制字符串传递，Java/数据库使用 Decimal。
- 比率在 API 中使用小数形式，例如 `0.12` 表示 12%。展示层负责百分比格式化。
- 不输出 NaN、Infinity 或用 `0` 代替不可计算值。统一返回 `value=null`、`status=NOT_AVAILABLE` 和 warning/error code。
- 中间计算保留完整精度；默认序列化比率最多 8 位小数、货币 2 位、每股价值 4 位。舍入仅发生在输出层，规则为 HALF_EVEN。
- 默认交易年为 252 个交易日；不得用自然日替代。

## 2. 输入与清洗

日线输入必须含：

```text
date, open, high, low, close, adjustedClose, volume
```

配对指标另需完整 `benchmarkPrices`。每次响应返回输入区间、有效样本量、丢弃数量和 warning。

处理顺序：

1. 解析 ISO 日期并按日期升序排序；日线日期代表交易所交易日。
2. 同一证券/Provider/日期只能有一条记录。完全相同的重复项去重并 warning；值冲突则失败 `DUPLICATE_DATE_CONFLICT`。
3. `adjustedClose <= 0` 的记录从收益计算中拒绝；若请求技术指标，OHLC 必须为正且 `low <= min(open, close) <= max(open, close) <= high`，否则失败。
4. 缺失值按指标处理，不做跨长缺口的价格插值。价格序列不前向填充。
5. 标的与基准按共同交易日 inner join；报告原始与对齐后样本数。共同样本不足时只让配对指标不可用，不阻止单证券指标。
6. 收益使用 `adjustedClose`；ATR 使用调整后 OHLC（若 Provider 不能提供一致调整 OHLC，则返回 `ADJUSTED_OHLC_UNAVAILABLE`）。
7. 所有输入携带来源快照 ID 和哈希；输出携带输入哈希集合。

## 3. 收益

简单日收益：

```text
r_t = adjustedClose_t / adjustedClose_(t-1) - 1
```

| 指标 | 公式/规则 | 最小样本 |
| --- | --- | --- |
| Daily Return | 上式 | 2 个价格 |
| Cumulative Return | `product(1 + r_t) - 1` | 2 个价格 |
| Annualized Return | `product(1+r_t)^(252/n) - 1` | 30 个收益 |
| CAGR | `(ending / beginning)^(365.2425 / calendarDays) - 1` | 间隔至少 30 天 |
| Rolling Return | `P_t / P_(t-window) - 1`，窗口按交易日 | window+1 个价格 |
| Excess Return | 标的累计/年化收益减基准同口径收益 | 30 个共同收益 |

Annualized Return 使用有效交易样本；CAGR 使用真实首尾日期。两者均返回，不能互换名称。
标量 API 中 `latest_daily_return` 发布区间最后一个有效交易日的日收益，
`rolling_return_20` 发布区间末日对应的最近 20 个有效交易日滚动收益；历史序列由后续图表接口提供，
不得把上述标量描述为完整序列。

## 4. 风险与基准指标

年化无风险利率 `rfAnnual` 转换为日利率：

```text
rfDaily = (1 + rfAnnual)^(1/252) - 1
```

| 指标 | 定义 |
| --- | --- |
| Daily Volatility | 日收益样本标准差，`ddof=1` |
| Annualized Volatility | `dailyStd * sqrt(252)` |
| Downside Deviation | `sqrt(mean(min(r_t - marDaily, 0)^2)) * sqrt(252)`；`marAnnual` 默认 0 |
| Maximum Drawdown | `min(P_t / runningMax_t - 1)`，结果为非正数 |
| Drawdown Duration | 从峰值后首次低于峰值到恢复峰值的交易日数；未恢复则计算到区间末尾并标记 `OPEN_DRAWDOWN` |
| Historical VaR 95% | 正损失口径：`max(0, -quantile(r, 0.05))` |
| Historical CVaR 95% | `r <= q05` 样本的平均损失取正值；尾部无样本则不可用 |
| Sharpe | `(mean(r)-rfDaily) / std(r) * sqrt(252)` |
| Sortino | `(mean(r)-marDaily) / downsideStdDaily * sqrt(252)` |
| Calmar | `annualizedReturn / abs(maxDrawdown)` |
| Beta | `cov(asset, benchmark) / var(benchmark)`，共同日期、样本协方差 |
| Alpha | `((mean(asset)-rfDaily) - beta*(mean(benchmark)-rfDaily)) * 252` |
| Correlation | 共同日收益 Pearson correlation |

最小样本：波动率/Sharpe/Sortino 30，VaR/CVaR 100，Beta/Alpha/Correlation 60。分母绝对值小于 `1e-12` 时返回 `ZERO_DENOMINATOR`。

`quant_v1` 的 5% 分位数固定使用线性插值：排序后位置为 `(n-1)*0.05`，在相邻两个
次序统计量之间线性插值。Drawdown Duration 按价格低于当前 running maximum 的连续交易日计数；
新高或回到前高时归零，区间末仍未恢复时返回已持续天数并附 `OPEN_DRAWDOWN`。

## 5. 技术指标

| 指标 | 规则 |
| --- | --- |
| SMA 20/50/200 | 完整窗口算术平均，窗口未满为 null |
| EMA 12/26 | `adjust=False` 递归 EMA，`alpha=2/(span+1)`，窗口未满不发布 |
| RSI 14 | Wilder RMA，`alpha=1/14`；无损失且有收益为 100，无收益且有损失为 0，两者均为 0 为 50 |
| MACD | `EMA12 - EMA26` |
| MACD Signal | MACD 的 EMA9 |
| Bollinger Bands | SMA20 ± 2 × 20 日样本标准差 |
| ATR 14 | True Range 的 Wilder RMA；`TR=max(high-low, abs(high-prevClose), abs(low-prevClose))` |
| 52 Week Distance | 相对最近最多 252 个交易日最高/最低 adjusted close 的距离 |
| Volume Moving Average | 20 日平均成交量；负成交量非法 |

ATR 先按每条记录的 `adjustedClose / close` 比例同步缩放 open/high/low/close，再计算 True
Range，避免拆股造成虚假跳空；若不能形成一致的复权 OHLC，则不得用未复权高低价替代。
EMA 从首个有效观测初始化，但只有达到规定窗口后才发布；Bollinger 使用样本标准差 `ddof=1`。

### 趋势分类

少于 200 个有效交易日输出 `INSUFFICIENT_DATA`。否则按最后一个交易日计算五项分数：

1. `close > SMA20` 为 +1，否则 -1；
2. `SMA20 > SMA50` 为 +1，否则 -1；
3. `SMA50 > SMA200` 为 +1，否则 -1；
4. `SMA50 / SMA50_20_sessions_ago - 1`：大于 1% 为 +1，小于 -1% 为 -1，其余 0；
5. `close / SMA200 - 1`：大于 5% 为 +1，小于 -5% 为 -1，其余 0。

映射：

| 总分 | 分类 |
| --- | --- |
| 4 到 5 | `STRONG_UPTREND` |
| 2 到 3 | `UPTREND` |
| -1 到 1 | `RANGE` |
| -3 到 -2 | `DOWNTREND` |
| -5 到 -4 | `STRONG_DOWNTREND` |

分类输出同时返回五项原始信号，便于测试和解释。

## 6. 基本面

财务期间必须按公司财年和 `periodEndDate` 对齐；TTM、季度和年度不得混算。币种或单位不同先通过注册的确定性转换步骤统一，否则不可比较。

| 指标 | 公式 |
| --- | --- |
| Revenue Growth YoY | `revenue_t / revenue_(t-4 quarters or prior FY) - 1` |
| Revenue CAGR | `(revenue_end/revenue_start)^(1/years)-1`，起点和终点必须为正 |
| Gross Margin | `grossProfit / revenue` |
| Operating Margin | `operatingIncome / revenue` |
| Net Margin | `netIncome / revenue` |
| FCF Margin | `(operatingCashFlow - capitalExpenditure) / revenue`；CapEx 统一为正支出绝对值 |
| EPS Growth | `dilutedEPS_t / dilutedEPS_prior - 1`；跨零或 prior<=0 返回不可解释 warning |
| Debt to Equity | `totalDebt / totalEquity`；equity<=0 不发布比率 |
| Net Debt | `totalDebt - cashAndEquivalents` |
| Current Ratio | `currentAssets / currentLiabilities` |
| Interest Coverage | `EBIT / interestExpense`；利息费用统一为正绝对值 |
| ROE | `netIncome / averageEquity` |
| ROA | `netIncome / averageAssets` |
| ROIC | `NOPAT / averageInvestedCapital`，`NOPAT=EBIT*(1-effectiveTaxRate)` |
| Share Dilution | `dilutedShares_t / dilutedShares_prior - 1` |
| CapEx Trend | 至少 4 期 CapEx 的线性斜率与同比序列，单独返回，不压缩成 LLM 判断 |

有效税率限制在 `[0, 1]`；超出时使用 `NOT_AVAILABLE` 而不是截断。负利润公司 P/E 返回
`NOT_APPLICABLE`，并附 `NEGATIVE_EARNINGS` warning。

跨期选择固定如下：同比优先使用两个相隔一年的 TTM，其次相邻年度，再其次当前季度与四个季度前；
Revenue CAGR 只使用同币种年度序列的首尾日期；CapEx slope 对同期间类型、同币种、按日期升序的
至少四个绝对支出值执行普通最小二乘直线斜率。ROE/ROA/ROIC 的平均资产负债口径使用当前期与
同期间类型、同单位的最近前一期。任何期间或单位不能兼容时返回 `INSUFFICIENT_DATA` 或
`SOURCE_UNIT_MISMATCH`，不得跨组拼接。

## 7. 估值

| 指标 | 公式 |
| --- | --- |
| Market Capitalization | `currentPrice × dilutedShares` |
| P/E | `price / dilutedEPS_TTM`，EPS<=0 不适用 |
| Forward P/E | 仅在可追踪预测数据可用时计算 |
| Price/Sales | `marketCap / revenueTTM` |
| Price/Book | `marketCap / commonEquity` |
| Enterprise Value | `marketCap + totalDebt + preferredEquity + minorityInterest - cash` |
| EV/Revenue | `EV / revenueTTM` |
| EV/EBITDA | `EV / EBITDA_TTM`；EBITDA<=0 不适用 |
| FCF Yield | `FCF_TTM / marketCap` |

估值必须返回价格有效日期、财务期间、币种、股份口径和来源。历史/同行估值没有合规数据源时为 `NOT_AVAILABLE`。

Forward P/E 只有在请求中存在带 SourceSnapshot 的 `forwardDilutedEPS` 且单位为
`{currency}_PER_SHARE` 时计算；否则返回 `FORECAST_DATA_UNAVAILABLE`。普通股 EPS、EBITDA 或
权益为非正时，相应 P/E、EV/EBITDA 或 P/B 使用 `NOT_APPLICABLE`。ETF 的公司基本面和公司估值
指标统一返回 `ETF_NOT_APPLICABLE`。企业价值优先使用同币种的债务、现金、优先股和少数股东权益；
缺少兼容资产负债表时才使用显式 scenario `netDebt`，且单位不匹配会保留 warning。

## 8. Bull/Base/Bear 情景引擎

MVP 使用可解释的 EV/EBITDA 方法：

```text
forecastRevenue = baseRevenue * (1 + revenueGrowth)
forecastEbitda = forecastRevenue * targetEbitdaMargin
impliedEnterpriseValue = forecastEbitda * evToEbitdaMultiple
impliedEquityValue = impliedEnterpriseValue - netDebt
rawImpliedPrice = impliedEquityValue / dilutedShares
impliedPrice = max(0, rawImpliedPrice)
upsideDownside = impliedPrice / currentPrice - 1
weightedScenarioValue = sum(impliedPrice_i * probability_i)
```

验证：

- 三个概率在 `1e-8` 容差内合计为 1；
- 股份数和当前价格必须大于 0；
- 增长率、利润率和倍数必须带单位与来源/假设类型；
- `targetEbitdaMargin` 可为负，但若导致非正 EBITDA，EV/EBITDA 方法输出限制 warning；
- 负的 `rawImpliedPrice` 不隐藏，保留原值并将可展示价格下限设为 0；
- 每个输入标为 `FACT`（来源值）或 `OPINION`（情景假设）；
- 情景价格称为“隐含情景值”，不得称为确定目标价。

后续可增加 `EV_REVENUE` 方法，但必须使用独立 `calculationVersion`，不能改变 `quant_v1` 结果。

`quant_v1` 的敏感性由 BULL/BASE/BEAR 三组显式增长率、EBITDA margin 和倍数假设共同表达；
不在服务端自动生成未由请求提供的 ±margin/±multiple 网格，避免把系统自造假设伪装成用户输入。
若后续增加二维敏感性矩阵，必须扩展请求 Schema、标记每个假设来源并升级 calculationVersion。

## 9. 输出状态与 Warning

每个指标返回：

```text
name, value, unit, status,
sampleSize, periodStart, periodEnd,
calculationVersion, inputSnapshotIds,
warnings[]
```

标准状态：`AVAILABLE`、`NOT_AVAILABLE`、`NOT_APPLICABLE`、`INVALID_INPUT`。

最低 warning/error 集：

- `INSUFFICIENT_DATA`
- `MISSING_VALUE_DROPPED`
- `DUPLICATE_DATE_REMOVED`
- `DUPLICATE_DATE_CONFLICT`
- `NON_POSITIVE_PRICE`
- `BENCHMARK_ALIGNMENT_REDUCED_SAMPLE`
- `ZERO_DENOMINATOR`
- `NEGATIVE_EARNINGS`
- `OPEN_DRAWDOWN`
- `ADJUSTED_OHLC_UNAVAILABLE`
- `SOURCE_UNIT_MISMATCH`

## 10. 测试基线

每个指标至少覆盖：正常数据、空数组、单点、缺失、重复、乱序、零/负价格、常量序列、日期不一致、跨零财务、除零和最小样本边界。使用固定黄金数据集验证预期值，并对以下性质做测试：

- 累计收益与价格首尾比一致；
- 最大回撤始终 `<= 0`；
- Volatility、VaR、CVaR 不为负；
- Correlation 在 `[-1,1]`；
- 三种情景概率加权值位于有限输入的最小与最大情景值之间；
- 输入顺序变化不改变清洗后结果；
- 相同输入哈希和 calculationVersion 产生字节稳定的规范化 JSON。
