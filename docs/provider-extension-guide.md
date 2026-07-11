# Provider 扩展指南

最后更新：2026-07-11

## 1. 入口门禁

新增 Provider 前先完成 [`provider-license-matrix.md`](provider-license-matrix.md) 的持久化、缓存、
派生、终端展示、Markdown/HTML/PDF 导出、历史复现和再分发权利。任何一项不允许或不明确，
Adapter 不得启用。

## 2. 实现顺序

1. 实现领域端口：Market、Fundamental、Filing 或 Macro；Controller/Entity 不得依赖供应商 DTO。
2. 新增严格配置属性：endpoint、timeout、body limit、rate、attempt 和 credential 完整性。
3. endpoint 必须固定 HTTPS official host；loopback 仅测试；拒绝 userinfo、IP literal 和 lookalike host。
4. 使用结构化参数构造路径，禁止用户 URL；对 content-type、解压后 bytes 和 Schema 失败关闭。
5. 注册 raw/normalized hash、retrieved/published/effective date、provider、schema、license policy 和归属。
6. 通过 `ProviderRuntime` 使用包含 provider/schema/完整 subject hash 的有界 Redis cache、熔断和指标。
7. 定义永久与临时错误；只对 429、502、503 和网络超时执行有界退避。
8. REAL 解析必须命中非 Demo security master；禁止缺失时回退 Mock。
9. 报告发布必须再次验证 Research/Report/Source/Evidence dataMode 一致。

## 3. 必需测试

- 官方与 loopback endpoint 允许；userinfo、私网元数据、lookalike host 拒绝；
- 401/403/4xx 不重试，429/502/503/timeout 有界重试；
- content-type、超大 body、空响应、缺字段、非法日期/数值、单位/期间错误失败关闭；
- cache hit/miss/write/error/oversize，Redis 故障直取，cache key 不跨 provider/schema/subject；
- source raw/normalized hash、日期、归属和 policy version 不可变；
- Mock/REAL/MIXED_TEST 隔离、所有权、历史报告复现和三格式导出；
- Contract fixture 不访问真实外部服务；真实 sandbox 只作为单独人工验收。

## 4. Market Adapter 特有要求

必须提供 adjusted OHLCV、原始 OHLCV、币种、交易所、时区、interval、公司行动语义、数据延迟和
有效日期。Contract Test 至少覆盖拆股/分红、停牌/缺日、重复日期、乱序、负/零价格、ETF、类别股、
代码变化和退市证券。无法证明 adjusted close 语义时不得计算或发布收益、Beta、技术指标和情景上行。

## 5. 完成定义

代码、许可记录、Contract Test、故障注入、来源归属、缓存/指标、REAL 全 Worker、旧报告复现与
GitHub Compose Gate 同时通过，才能把 Provider 标为 enabled。只配置 key 不等于完成接入。
