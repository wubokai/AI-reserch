# Python Analytics 内部 API

## 1. 边界

Analytics 是 Java API 调用的内部确定性计算服务，默认地址 `http://analytics:8000`。浏览器不得直接调用。它不访问数据库、Redis、Provider 或 LLM；所有输入都由 Java 从已注册 SourceSnapshot 组装。

机器契约位于 `packages/shared-schemas/analytics/`。金额和比例使用十进制字符串进入/离开服务，Analytics 内部转换为 `float64`；NaN/Infinity 永不序列化。

## 2. 端点

| 方法 | 路径 | 作用 | 计划阶段 |
| --- | --- | --- | --- |
| GET | `/analytics/v1/health` | 进程健康与版本 | Phase 1 |
| POST | `/analytics/v1/returns` | 收益与滚动收益 | Phase 4 |
| POST | `/analytics/v1/risk` | 风险、回撤和基准指标 | Phase 4 |
| POST | `/analytics/v1/technicals` | SMA/EMA/RSI/MACD/Bollinger/ATR/趋势 | Phase 4 |
| POST | `/analytics/v1/fundamentals` | 标准化财务指标 | Phase 4 |
| POST | `/analytics/v1/valuation` | 可用估值指标 | Phase 4 |
| POST | `/analytics/v1/scenarios` | Bull/Base/Bear 情景引擎 | Phase 3-4 |
| POST | `/analytics/v1/full-analysis` | 组合上述模块、复用清洗结果 | Phase 3-4 |

除 health 外，所有 POST 使用相同 envelope，并由端点只执行对应模块。Java 设置 `X-Request-Id`、`X-Research-Id` 和内部服务认证头；不得把用户 Bearer Token 转发给 Analytics。

## 3. Full Analysis 请求

规范见 `full-analysis-request.schema.json`。关键字段：

- `schemaVersion=analytics_full_request_v1`；
- `symbol`、`securityType`、`periodStart`、`periodEnd`；
- `prices`：完整 adjusted OHLCV；
- `benchmarkSymbol` 与 `benchmarkPrices`：Beta/Alpha/Correlation/Excess Return 必需；
- `riskFreeRateAnnual` 与 `minimumAcceptedReturnAnnual`；
- 可选、已标准化 `fundamentals`；
- 可选 `scenarioInput`；
- 每条输入带 SourceSnapshot ID，根对象带规范化 input hash。

ATR 绝不接受只有 close 的请求。基准名称存在但 `benchmarkPrices` 为空时，配对指标返回 `NOT_AVAILABLE`，不能抓取或猜测基准数据。

## 4. 响应

规范见 `full-analysis-response.schema.json`。每个 Metric 返回：

```text
name
value (decimal string or null)
unit
status: AVAILABLE | NOT_AVAILABLE | NOT_APPLICABLE | INVALID_INPUT
sampleSize
periodStart / periodEnd
calculationVersion
inputSnapshotIds
warnings[]
```

响应级 `status` 为 `COMPLETED | COMPLETED_WITH_WARNINGS | FAILED`，不映射 Java Research 终态。Java 根据所请求模块、Evidence 和报告策略裁决 `COMPLETED/PARTIALLY_COMPLETED/FAILED`。

## 5. 错误与 HTTP

- `200`：请求格式和核心输入合法；单个指标不可用通过 Metric status/warning 表达。
- `400`：JSON/Schema 错误。
- `422`：日期、价格、单位或场景概率等语义非法，整个请求不能安全计算。
- `413`：超过记录上限。
- `500`：未预期内部错误，响应不含堆栈。

统一错误：

```json
{
  "timestamp": "2026-07-09T18:00:00Z",
  "status": 422,
  "code": "DUPLICATE_DATE_CONFLICT",
  "message": "Conflicting price bars were supplied for one date.",
  "requestId": "req_...",
  "researchId": "uuid",
  "details": {}
}
```

Analytics 不重试。Java 负责调用超时、分类重试和熔断；相同 input hash 与 calculationVersion 必须产生规范化字节稳定结果。

## 6. 资源限制

- 每个价格序列默认最多 5,000 条日线；
- 同一请求标的和基准合计最多 10,000 条；
- 请求 body 默认 10 MB；
- full-analysis 默认超时 30 秒，单模块 10 秒；
- 计算过程不得写全局可变状态或落本地持久文件。

## 7. Contract Test

Java consumer 与 Python provider 共享 JSON fixture，至少覆盖：

1. adjusted OHLCV 和 benchmarkPrices 正常往返；
2. decimal string、日期、枚举和 null 状态；
3. 空、单点、重复冲突、乱序、非正价格和基准错位；
4. 未知字段拒绝；
5. 相同输入哈希的确定性输出；
6. schemaVersion/calculationVersion 不兼容时快速失败。
