# Phase 7 Provider 许可矩阵

最后复核：2026-07-11

| 来源 | 数据 | PostgreSQL | Redis | 派生计算 | Web 展示 | MD/HTML/PDF | 决策 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SEC EDGAR | Filing / XBRL Companyfacts | 允许进入本项目公开来源快照策略 | 允许，遵守访问政策 | 允许，保留 concept lineage | 允许，显示 SEC 来源 | 允许，保留来源 | 已选择 |
| FRED | 宏观 observation/vintage | 允许，受序列第三方权利约束 | 允许，受条款约束 | 允许 | 需显示规定归属 | 需保留规定归属 | 已选择 |
| Tiingo Individual Starter | split + cash-dividend adjusted OHLCV | 仅项目负责人个人内部使用 | 最长七天可丢失缓存 | 允许个人内部研究 | 仅 Tailscale 私网内本人查看 | 仅本人内部下载，不得分享 | **已选择，严格私有** |
| Twelve Data 公共订阅 | adjusted OHLCV 候选 | 仅 Internal Use/计划规定 | 受文档期限约束 | 可生成不可逆 Derived Data | 外部展示需额外权利 | 属 Redistribution，需额外权利 | 停止接入 |

## Tiingo 决策边界

- 条款版本：Tiingo Terms of Use Version 1，最后更新 2026-02-18；许可策略标识
  `tiingo_individual_internal_v1_2026_02_18`。
- Starter 当前为 `$0/month`，API 额度为每月 500 个唯一代码、每小时 50 次、每天 1000 次、每月
  1 GB；应用只使用 EOD endpoint，Authorization Token 仅在 header 中传递。
- EOD 响应同时含 raw 与 `adjOpen/adjHigh/adjLow/adjClose/adjVolume`；本项目只把现金分红和拆股
  复权后的 OHLCV 送入确定性计算，并保存精确原始响应 hash。
- 许可仅覆盖项目负责人个人内部消费。Tailscale 私网、单一 owner JWT 和不公开端口是许可控制的一部分；
  不得把页面、图表或 MD/HTML/PDF 发给其他人，也不得公开部署。
- 若增加第二位实际用户、公开 URL、客户演示或商业使用，必须先停用 Tiingo Starter 并取得相应
  Commercial/Redistribution 书面许可。

## 运行时门禁

只有同时设置以下值才启用 Tiingo：

- `MARKET_DATA_PROVIDER=tiingo`；
- `MARKET_DATA_LICENSE_CONFIRMED=true`；
- `MARKET_DATA_LICENSE_POLICY_VERSION=tiingo_individual_internal_v1_2026_02_18`；
- 独立的个人 `TIINGO_API_KEY`。

以下边界仍然适用：

- 服务器端历史存储期限和删除义务；
- Redis 缓存期限与缓存范围；
- adjusted OHLCV 的拆股、现金分红和复权定义；
- Web 对终端用户展示原始值和图表的权利；
- Markdown、HTML、PDF 下载及向用户提供文件的权利；
- 派生收益、风险、技术指标与报告文本的权利；
- 交易所归属、延迟标记、用户数量与设备计费；
- 美股、ETF、类别股、退市/更名证券、至少五年历史覆盖；
- 请求速率、月配额、SLA、超额费用和生产支持；
- 合同/订阅版本、开始/结束日期和内部责任人。

运行时确认变量只是防误配置门禁，不能扩大条款授予的个人内部使用权。
