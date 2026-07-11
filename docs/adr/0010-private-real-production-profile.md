# ADR-0010：单 owner 私有 REAL 生产配置

状态：接受

日期：2026-07-11

## 决策

项目负责人选择以下组合：

- Tiingo Individual Starter 作为 EOD Market Provider；
- LanYi `https://lanyapi.com/v1/` 作为 Responses-compatible LLM endpoint；
- 24 小时云 VM，通过 Tailscale Serve 私网 HTTPS 访问，不公开 Funnel；
- R3：Research/Evidence 1095 天、独立 raw provider blob 365 天、加密备份 90 天；
- SEC 自动访问身份为 `AI Quant Research Assistant bw2754@nyu.edu`。

## 约束

Tiingo Individual 仅限项目负责人个人内部消费。系统必须保持单 owner、私有 tailnet 和 loopback 宿主
端口；报告和数据导出不得分享。任何第二用户、公开 URL、演示客户或商业用途都需要新的许可 ADR。

LanYi 不是 OpenAI 官方 endpoint，会接触发送的 Evidence Pack。允许列表只加入精确
`lanyapi.com` HTTPS host 和 `/v1` path；provider audit 记录 `LANYI`。API Key、价格版本和单价必须由
服务器 Secret 注入，未知计价时失败关闭。任何曾出现在聊天或日志中的 Key 必须撤销。

生产认证采用双层边界：Tailscale 认证实际设备，Next.js BFF 再用至少 256-bit Secret 签发 60 秒 HS256
JWT 给内部 Java API。API 校验 issuer、audience、签名、有效期和 owner email；Basic 只保留开发/测试。

## 后果

- 个人阶段可用免费行情与免费云额度，把固定成本降到最低；Oracle 免费容量不足时退回付费 VPS。
- 应用不能公开分享 Tiingo 图表或导出，开源代码使用者必须使用自己的 Tiingo Token。
- PostgreSQL normalized Evidence 为三年复现链路保留；不单独保存 provider raw body，只保存 raw hash。
- 到期 Research 先软归档并保留不可变审计，legal hold 阻止自动归档；备份文件按 90 天物理删除。
