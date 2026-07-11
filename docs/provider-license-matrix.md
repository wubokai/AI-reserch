# Phase 7 Provider 许可矩阵

最后复核：2026-07-10

| 来源 | 数据 | PostgreSQL | Redis | 派生计算 | Web 展示 | MD/HTML/PDF | 决策 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SEC EDGAR | Filing / XBRL Companyfacts | 允许进入本项目公开来源快照策略 | 允许，遵守访问政策 | 允许，保留 concept lineage | 允许，显示 SEC 来源 | 允许，保留来源 | 已选择 |
| FRED | 宏观 observation/vintage | 允许，受序列第三方权利约束 | 允许，受条款约束 | 允许 | 需显示规定归属 | 需保留规定归属 | 已选择 |
| Twelve Data 公共订阅 | adjusted OHLCV 候选 | 仅 Internal Use/计划规定 | 受文档期限约束 | 可生成不可逆 Derived Data | 外部展示需额外权利 | 属 Redistribution，需额外权利 | **停止接入** |
| 其他商业行情商 | adjusted OHLCV | 未确认 | 未确认 | 未确认 | 未确认 | 未确认 | 未选择 |

## Market 解锁证据

在以下项目全部有书面答案前，`MARKET_DATA_PROVIDER` 必须保持 `mock`：

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

公开网页只能完成初筛，不能替代供应商书面合同。运行时确认变量只是防误配置门禁，不能
自行创造法律权利。
