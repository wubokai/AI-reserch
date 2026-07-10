# Java REST API 契约（Phase 2）

本文定义 AI Quant Research Assistant 的外部 HTTP 契约。实现、前端客户端和契约测试均应以 [`openapi.yaml`](./openapi.yaml) 为机器可读的唯一事实来源；本文用于解释语义和约束。

## 1. 基本约定

- Base URL：`http://localhost:8080/api/v1`
- 媒体类型：除导出接口外，请求和响应均为 `application/json`。
- 字段命名：JSON 使用 `camelCase`；数据库使用 `snake_case`。
- 标识符：公开资源 ID 使用 UUID；Evidence 另有稳定的 `evidenceId`，格式如 `ev_01J...`。
- 时间：服务端一律保存和返回 UTC、RFC 3339 时间，例如 `2026-07-09T18:00:00Z`。
- 日期：使用 ISO 8601 `YYYY-MM-DD`。
- 金额和比率：不得用格式化字符串传递。金额包含 `value`、`currency`；比率以小数表示，例如 `0.12` 表示 12%。
- 追踪：客户端可传 `X-Request-Id`；服务端始终在响应头返回最终 `X-Request-Id`。
- 认证：Phase 2 仅在 development/test profile 下支持显式启用的 Basic 演示用户。Bearer Token 是生产契约，但正式接入延后；在此之前 production profile 启动即失败关闭，不能退化成 Basic 或匿名访问。
- 版本策略：`/api/v1` 只做向后兼容扩展；删除字段、改变字段含义或收紧枚举须发布新主版本。

## 2. 幂等性

所有 `POST` 接口必须携带 `Idempotency-Key`。推荐使用 UUID；允许 1–128 个可打印 ASCII 字符。

- 幂等范围为“认证用户 + HTTP 方法 + 路径 + Idempotency-Key”。
- 相同 key 和相同规范化请求体在 24 小时内重放时，返回第一次请求的 HTTP 状态码、响应体和 `Idempotency-Replayed: true`，不得重复入队、重复重试或重复取消。
- 相同 key 搭配不同请求体时返回 `409 IDEMPOTENCY_KEY_REUSED`。
- 只缓存已经形成业务结果的响应；连接在业务处理前失败时，客户端可安全重试。
- `GET`、`DELETE` 天然幂等，不接受 `Idempotency-Key` 作为业务语义。软删除重复调用仍返回 `204`。

## 3. 核心资源与状态机

### Research

Research 是一个异步研究任务。创建接口只做校验、证券解析预检查和入队，不等待完整报告生成。

状态枚举：

```text
CREATED -> QUEUED -> RESOLVING_SECURITY
  -> FETCHING_MARKET_DATA
  -> FETCHING_FUNDAMENTALS
  -> FETCHING_FILINGS
  -> FETCHING_MACRO_DATA
  -> VALIDATING_DATA
  -> RUNNING_QUANT_ANALYSIS
  -> ANALYZING_FUNDAMENTALS
  -> BUILDING_EVIDENCE
  -> GENERATING_REPORT
  -> VALIDATING_REPORT
  -> COMPLETED | PARTIALLY_COMPLETED | FAILED | CANCELLED
```

`progress` 范围为 0–100，且在一次执行中不得倒退。`PARTIALLY_COMPLETED` 表示已有可用结果，但存在缺失步骤或未完全通过报告验证。重试从首个失败或未完成步骤继续，已经成功且输入哈希未变化的步骤不得重新执行。

### ReportVersion

- 报告版本是不可变快照；同一 Research 下 `version` 从 1 单调递增。
- `GET /research/{id}` 返回任务元数据和最新报告（若存在）。
- `GET /research/{id}/reports` 返回所有版本摘要；`GET /research/{id}/reports/{version}` 返回指定完整版本。
- 修复验证失败、改变输入数据或重新生成叙述时创建新版本，不覆盖旧版本。
- 导出默认导出最新版本，也可通过 `reportVersion` 固定版本。

### Claim 与 Evidence

每条报告结论必须结构化为 Claim：

```json
{
  "claimId": "cl_01J...",
  "text": "FY2025 revenue increased year over year.",
  "claimType": "FACT",
  "confidence": 0.96,
  "material": true,
  "evidenceIds": ["ev_01J..."]
}
```

`claimType` 只能是：

- `FACT`：外部来源提供的事实；
- `CALCULATION`：后端根据事实确定性计算的结果；
- `INFERENCE`：模型基于事实和计算作出的推断；
- `OPINION`：明确标注的主观研究观点。

Claim 的 `confidence` 表示该结论在已关联 Evidence 和 Calculation 支持下的可信/支持程度，范围为 0–1；它不是来源自身的质量评分。

重要 Claim（`material=true`）至少关联一个 Evidence。Evidence 是 Evidence Registry 中的不可变记录：

```json
{
  "evidenceId": "ev_01J...",
  "evidenceType": "FINANCIAL_METRIC",
  "title": "FY2025 revenue",
  "summary": "Revenue reported in the FY2025 10-K.",
  "value": 25000000000,
  "unit": "USD",
  "sourceName": "SEC EDGAR",
  "sourceUrl": "https://www.sec.gov/Archives/...",
  "sourceType": "SEC_FILING",
  "publishedAt": "2026-02-01T00:00:00Z",
  "retrievedAt": "2026-07-09T18:00:00Z",
  "effectiveDate": "2025-12-31",
  "isPrimarySource": true,
  "freshnessStatus": "FRESH",
  "qualityScore": 0.99,
  "isDemoData": false
}
```

`qualityScore` 只衡量来源/记录质量，不表示 Claim 获得支持的程度。模型只能引用 Registry 中已有的 `evidenceId`。发布前验证器必须检查 Evidence 存在性、数字和日期一致性、引用支持关系、Claim 类型及过期数据提示。

## 4. Mock 与测试数据标记

本地开发、CI 或演示环境可显式启用 Mock Provider 完成流程；缺少真实 Provider 凭据本身不得触发静默回退，演示数据也不得伪装成真实市场数据。

- Research、报告和列表项返回 `dataMode`: `REAL | MOCK | MIXED_TEST`。
- 每条 Mock Evidence 返回 `isDemoData=true`，来源快照也保存相同标志。
- Provider 状态返回 `mode`: `REAL | MOCK | DISABLED`，且永不返回 API Key。
- `MOCK` 在 API 中保持机器可读枚举；页面和全部导出必须持续显示 `DEMO DATA - NOT REAL MARKET DATA`。
- `MIXED_TEST` 仅用于自动测试和故障演练，必须带测试警告，禁止进入普通用户发布、分享或导出流程。
- `REAL` 任务在真实 Provider 失败时不得静默切换到 Mock；应失败或以结构化 warning 标记部分完成。

## 5. 接口一览

| 方法 | 路径 | 成功状态 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/research` | `202` | 创建并入队研究任务 |
| `GET` | `/research` | `200` | 分页检索历史任务 |
| `GET` | `/research/{researchId}` | `200` | 获取任务详情和最新报告 |
| `GET` | `/research/{researchId}/status` | `200` | 获取进度和步骤状态 |
| `POST` | `/research/{researchId}/retry` | `202` | 从失败/未完成步骤继续 |
| `POST` | `/research/{researchId}/cancel` | `202` | 请求协作式取消 |
| `DELETE` | `/research/{researchId}` | `204` | 软删除任务及其用户可见历史 |
| `GET` | `/research/{researchId}/evidence` | `200` | 分页获取 Evidence Registry |
| `GET` | `/research/{researchId}/reports` | `200` | 分页获取报告版本摘要 |
| `GET` | `/research/{researchId}/reports/{version}` | `200` | 获取指定完整报告版本 |
| `GET` | `/research/{researchId}/export` | `200` | 导出最新或指定报告版本 |
| `GET` | `/securities/search` | `200` | 搜索美股普通股和 ETF |
| `GET` | `/providers/status` | `200` | 获取脱敏的 Provider 状态 |
| `GET` | `/health` | `200`/`503` | 服务及关键依赖健康检查 |

## 6. 创建研究

```http
POST /api/v1/research
Idempotency-Key: 894e71a7-fdf4-48d4-91ca-0e119e7db7d6
Content-Type: application/json

{
  "query": "分析 MU 未来 12 个月的主要增长动力和风险",
  "symbol": "MU",
  "locale": "zh-CN",
  "benchmark": "QQQ",
  "period": "5y",
  "reportDepth": "STANDARD",
  "includeTechnicalAnalysis": true,
  "includeFundamentalAnalysis": true,
  "includeMacroAnalysis": true
}
```

`query` 必填；`symbol` 与 `companyName` 至少提供一个。`locale` 可选且只接受 `zh-CN | en-US`；未提供时服务端根据 `query` 语言确定并把最终值持久化，不能使用用户时区推断语言。创建线程只查询本地 `securities`：已知但 inactive/unsupported 的证券返回 `422 INVALID_SYMBOL`，已知的 symbol/companyName 矛盾返回 `422 SECURITY_MISMATCH`。未知但格式合法的证券仍入队，由持久 `RESOLVE_SECURITY` 步骤在 Phase 3 通过 Provider 解析；请求线程不调用外网。省略时 `period=5y`、`reportDepth=STANDARD`，三个分析开关均为 `true`。MVP 只接受美国上市普通股和 ETF。

```http
HTTP/1.1 202 Accepted
Location: /api/v1/research/018f3d92-a9a8-7b56-b451-f41f5b889f2a
Retry-After: 2

{
  "researchId": "018f3d92-a9a8-7b56-b451-f41f5b889f2a",
  "status": "QUEUED",
  "dataMode": "REAL",
  "createdAt": "2026-07-09T18:00:00Z",
  "links": {
    "self": "/api/v1/research/018f3d92-a9a8-7b56-b451-f41f5b889f2a",
    "status": "/api/v1/research/018f3d92-a9a8-7b56-b451-f41f5b889f2a/status"
  }
}
```

## 7. 列表与分页

`GET /research` 支持：

- `page`：零起始页码，默认 0；
- `size`：每页 1–100，默认 20；
- `symbol`、`status`、`from`、`to`、`q`：筛选；
- `sort`：`createdAt,desc`（默认）、`createdAt,asc`、`updatedAt,desc`、`updatedAt,asc`。

所有分页响应使用同一结构：

```json
{
  "items": [],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 0,
    "totalPages": 0,
    "first": true,
    "last": true
  }
}
```

Evidence 和报告版本列表也支持 `page`、`size`。默认不返回软删除记录。

## 8. 状态、重试、取消和删除

- `GET /research/{id}/status` 返回任务状态、进度、当前步骤、步骤尝试次数、可恢复性和最后错误。
- `POST /research/{id}/retry` 仅允许 `FAILED` 或 `PARTIALLY_COMPLETED`。请求体可指定 `fromStep`；服务端会拒绝跳过必需依赖的请求。
- `POST /research/{id}/cancel` 是协作式取消：成功接收返回 `202`，任务可能短暂处于原执行状态并带 `cancellationRequested=true`，最终进入 `CANCELLED`。`COMPLETED | PARTIALLY_COMPLETED | CANCELLED` 返回已有快照的 `202` no-op；`FAILED` 返回 `409 INVALID_STATE_TRANSITION`，因为此时只能重试或删除。
- 已处于不接受该命令的终态时返回 `409 INVALID_STATE_TRANSITION`；相同幂等请求重放除外。
- `DELETE /research/{id}` 设置 `deletedAt/deletedBy`，不物理删除审计、Evidence 或报告。删除后普通读取返回 `404 RESEARCH_NOT_FOUND`。

## 9. 导出

```http
GET /api/v1/research/{researchId}/export?format=pdf&reportVersion=2
```

- `format`: `pdf | markdown | html`，默认 `pdf`。
- `reportVersion` 省略时导出最新版本。
- 响应设置安全的 `Content-Disposition` 和与格式匹配的 `Content-Type`。
- 报告尚未形成时返回 `409 REPORT_NOT_READY`；PDF 生成失败返回 `503 PDF_GENERATION_FAILED`，不影响 HTML/Markdown 报告可用性。

## 10. 统一错误

所有非 2xx JSON 错误采用 `application/problem+json`：

`researchId` 始终存在；错误尚未绑定 Research 时其值为 JSON `null`。已解析路径上的 `RESEARCH_NOT_FOUND` 保留请求中的 UUID，便于客户端关联，同时仍用相同 404 文案隐藏所有权差异。

```json
{
  "type": "https://api.aiquant.local/problems/insufficient-market-data",
  "title": "Insufficient market data",
  "timestamp": "2026-07-09T18:00:00Z",
  "status": 422,
  "code": "INSUFFICIENT_MARKET_DATA",
  "message": "Not enough market data to calculate 200-day moving average",
  "requestId": "req_01J...",
  "researchId": "018f3d92-a9a8-7b56-b451-f41f5b889f2a",
  "retryable": false,
  "details": {
    "requiredSamples": 200,
    "actualSamples": 118
  }
}
```

常见映射：

| HTTP | 代码示例 | 语义 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | JSON/查询参数格式错误 |
| `401` | `UNAUTHORIZED` | 未认证或 token 无效 |
| `403` | `FORBIDDEN`, `ACCOUNT_DISABLED` | 无资源访问权限或账号不是活动状态 |
| `404` | `ROUTE_NOT_FOUND`, `RESEARCH_NOT_FOUND`, `REPORT_VERSION_NOT_FOUND` | 路由不存在，或资源不存在/已软删除 |
| `409` | `INVALID_STATE_TRANSITION`, `IDEMPOTENCY_KEY_REUSED`, `REPORT_NOT_READY` | 当前资源状态冲突 |
| `422` | `INVALID_SYMBOL`, `SECURITY_MISMATCH`, `INSUFFICIENT_MARKET_DATA`, `INVALID_EVIDENCE` | 语义校验失败 |
| `429` | `RATE_LIMITED`, `PROVIDER_RATE_LIMITED` | 本服务或上游限流；返回 `Retry-After` |
| `502` | `PROVIDER_RESPONSE_INVALID`, `ANALYTICS_FAILED`, `LLM_SCHEMA_INVALID` | 上游返回无效结果 |
| `503` | `PROVIDER_UNAVAILABLE`, `PDF_GENERATION_FAILED`, `SERVICE_UNAVAILABLE` | 临时不可用，可按 `retryable` 重试 |
| `504` | `PROVIDER_TIMEOUT`, `LLM_TIMEOUT` | 上游超时 |

数据库内部异常、密钥、完整外部响应和模型提示词不得出现在 `message` 或 `details` 中。

## 11. 健康与 Provider 状态

- `/health` 面向编排器，只有关键依赖（数据库或任务队列）不可用时返回 `503`。可降级 Provider 的失败不应令整个服务失活。
- `/providers/status` 面向前端设置页，返回能力、模式、健康度、最后成功调用时间、延迟和限流概况；不得返回凭据、凭据片段或内部异常堆栈。
- `DEGRADED` 表示仍可提供部分能力；前端应允许创建任务并展示预期缺失项。

## 12. 契约演进检查清单

对 API 的每次变更必须同时：

1. 更新 `docs/openapi.yaml`；
2. 生成或更新 Java DTO、TypeScript 类型和契约测试；
3. 验证 OpenAPI 3.1 文档可解析；
4. 为状态迁移、幂等重放、分页边界、软删除、Evidence 引用、Mock 水印及 `MIXED_TEST` 发布阻断添加测试；
5. 在破坏性变更时创建 `/api/v2`，不得悄悄改变 v1 语义。
