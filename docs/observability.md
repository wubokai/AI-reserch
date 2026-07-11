# 可观测性、SLO 与告警

最后更新：2026-07-11

## 1. 日志契约

API 与 Analytics 均输出单行 JSON。API 访问日志只记录 `method`、归一化 route、HTTP
status、durationMs、Request ID 和从路径安全提取的 Research ID；不记录 query string、请求体、
Authorization、Cookie、Provider key、Prompt 或模型原始响应。异常只公开稳定错误码和安全消息。

Request ID 只接受 1–100 位 `[A-Za-z0-9._:-]`，否则重新生成。Research UUID 在访问日志 route
中替换为 `{researchId}`，但可作为结构化 MDC 字段用于同任务关联。

## 2. Prometheus 指标

| 指标 | 标签 | 用途 |
| --- | --- | --- |
| `http_server_requests_seconds` | method、uri、status、outcome | API 吞吐、p95/p99 与错误率 |
| `research_jobs` | status | 队列、完成、部分完成、失败与取消存量 |
| `research_worker_active` | 无 | 当前进程活动 Step 数 |
| `research_worker_claims_total` | step | Worker 领取量 |
| `research_worker_executions_seconds` | step、outcome | Step 延迟与安全失败类型 |
| `research_queue_oldest_runnable_seconds` | 无 | 最老可执行 Step 等待时间 |
| `research_llm_cost_usd` | 无 | 数据库已记录的累计 LLM 估算成本 |
| `provider_requests_seconds` | provider、outcome | Provider 延迟、失败与熔断 |
| `provider_cache_total` | provider、outcome | hit/miss/write/error/oversize |
| `provider_retries_total` | provider、reason | 有界、分类后的重试 |

缓存命中率按 `hit / (hit + miss)` 计算；不要把 write/error 计入分母。所有标签来自固定枚举或经过
验证的错误码，不含 symbol、user、researchId、URL 或 key，避免高基数和敏感信息泄漏。

## 3. 发布 SLO

| 信号 | 发布目标 | 告警阈值 |
| --- | --- | --- |
| API 可用性 | 30 天非 5xx ≥ 99.5% | 5xx > 2% 持续 10 分钟 |
| API 延迟 | 非导出请求 p95 < 500 ms | p95 > 1 s 持续 15 分钟 |
| 队列等待 | 最老 runnable < 120 s | > 120 s 持续 10 分钟 |
| 研究失败 | 稳态失败率 < 10% | retained job 失败占比 > 10% 持续 15 分钟 |
| Provider | circuit open 为异常 | 10 分钟内出现 circuit_open |

可直接加载的规则在 [`ops/prometheus-alerts.yml`](../ops/prometheus-alerts.yml)。阈值是工程发布基线，
不是金融或业务承诺；真实流量上线后必须基于容量测试校准，并通过版本控制修改。

## 4. 性能与容量边界

- Provider Redis 缓存 TTL 最大七天，单条最大 20 MB，默认 5 MB；Redis 故障只降级为直取。
- API 入站 JSON 文档最大 64 KiB、20,000 token、32 层嵌套，单字符串最大 16 KiB；
  Provider 响应使用各 Adapter 独立的更大 byte limit，不复用浏览器入站限制。
- PDF 最大 25 MiB、200 页；外部资源全部禁用，候选字体最大 50 MiB。
- Worker 并发限制为 1–32，默认 2；数据库连接池默认 10。
- Provider、Analytics、LLM 均有独立超时与响应大小边界。

## 5. 最小仪表盘

值班视图至少包含：请求量/5xx/p95、各 Research 状态、最老 runnable、Worker active、各 Step
耗时、Provider 成功/失败/熔断、缓存命中率和累计 LLM 成本。仪表盘不得展示
原始 Prompt、Evidence 文本、API key 或用户查询。
