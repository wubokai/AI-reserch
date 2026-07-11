# 项目负责人外部输入清单

最后更新：2026-07-11

本清单只包含代码、测试和文档无法替代的外部事实。工程可自主部分全部完成并通过 Gate 后，再集中处理。

## 已确认决定

- Market：Tiingo Individual Starter，仅限项目负责人个人内部使用，禁止再分发。
- LLM endpoint：LanYi `https://lanyiapi.com/v1/`，模型 `gpt-5.5`、reasoning `xhigh`；第三方处理风险已接受。
- 部署：24 小时云主机 + Tailscale 私网，不公开 Funnel，不设置公共域名。
- 保留：R3，Research/Evidence 1095 天、独立 Provider raw 365 天、备份 90 天。
- SEC 联系邮箱：`bw2754@nyu.edu`。

## 仍需项目负责人最后提供

1. **旋转后的 LanYi Key**：此前发到聊天中的 Key 必须撤销；新 Key 只填服务器 `.env.production`。
2. **LanYi 准确计价**：input/cached-input/output 单价、币种、价格版本和生效日期。未知价格时预算门禁
   会阻止真实调用。
3. **Tiingo Token**：个人账户 Token，只填服务器 secret，不发送到聊天或 Git。
4. **FRED API Key**：为本应用单独创建的注册 Key。
5. **云账户**：优先 Oracle Cloud Always Free；无法获得容量时使用 Hetzner 4 GB VPS。
6. **Tailscale 登录**：个人 tailnet，用于服务器私网 TLS 入口。

所有凭据只通过安全 Secret Store/环境注入，不在对话、Git、Issue、日志或示例文件中粘贴真实值。
