# 项目负责人外部输入清单

最后更新：2026-07-11

本清单只包含代码、测试和文档无法替代的外部事实。工程可自主部分全部完成并通过 Gate 后，再集中处理。

## 阻塞完整 REAL v1 / Gate G7、G9

1. **Market Provider 书面权利**：供应商、套餐、合同/邮件，明确允许服务器缓存、PostgreSQL
   持久化、派生指标、终端展示、Markdown/HTML/PDF 导出、历史复现和所需再分发范围。
2. **Market 测试账户**：API 文档、sandbox/base URL、测试 key、配额、限流、延迟、adjusted close、
   拆股/分红语义、ETF/类别股/退市覆盖及必须展示的 attribution。

## 阻塞真实 LLM 生产验收

3. **OpenAI 配置**：项目 API key、当时选定的 report/validation model、服务端 HMAC secret、准确的
   input/cached-input/output 单价、价格版本和生效日期、单任务美元与调用数预算。
4. **质量评测决定**：允许上线的模型版本、代表性问题集和最低通过标准。测试框架不会自行猜模型或价格。

## 阻塞正式生产部署

5. **认证与部署目标**：OIDC/Bearer issuer、audience、角色/租户模型，以及目标云/主机、域名、TLS、
   Secret Store、数据库/Redis 服务、日志/Prometheus 后端和告警接收人。
6. **数据责任决定**：Research/Evidence/Report/LLM usage/审计/Provider raw/备份的书面保留期限、
   legal hold、删除/导出 SLA、允许区域和备份恢复目标。
7. **官方数据身份**：SEC User-Agent 中的应用名称与受监控联系邮箱、FRED 注册 API key。

所有凭据只通过安全 Secret Store/环境注入，不在对话、Git、Issue、日志或示例文件中粘贴真实值。
