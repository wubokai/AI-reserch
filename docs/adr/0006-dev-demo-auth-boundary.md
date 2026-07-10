# ADR 0006: dev-demo 认证边界

- 状态：Accepted
- 日期：2026-07-09

## 决策

闭环 MVP 不实现登录页；development/test 可使用固定 demo principal。但领域服务和 Repository 从第一天按 `owner_user_id` 授权，API 结构与正式认证保持一致。

生产环境满足任一条件时必须启动失败：`DEV_AUTH_MODE=demo`、默认密码、Mock-only 安全配置或缺少正式认证配置。

## 原因

登录 UI 不应阻塞研究闭环，但延后所有权边界会造成难以修补的 IDOR。固定 principal 只替代“如何认证”，不替代“是否授权”。

## 结果

- 所有 research/report/evidence/export 查询包含 owner 条件；
- Phase 2 添加跨用户 UUID 访问测试；
- 正式 OIDC/会话方案作为后续决策，不改变资源所有权模型；
- Provider 状态页只读且不回显密钥。
