# 数据保留与删除策略

最后更新：2026-07-11

## 已选生产策略：R3 长期研究

- Research、Report、Evidence、Claim、确定性计算和 LLM usage：1095 天。
- Provider 规范化输入：与 Evidence lineage 同期保留 1095 天；Tiingo 原始响应 body 不单独落库，
  只保留 SHA-256、retrieval metadata 与规范化快照，因此不存在超过 365 天的独立 raw blob。
- Redis Provider cache：默认 6 小时、最大 7 天，可随时清空。
- 加密 PostgreSQL 备份：每日一次，90 天自动删除；可选复制到私有对象存储。
- 到期终态 Research 由 `ResearchRetentionScheduler` 按批次软归档并写 SYSTEM audit；`legal_hold=true`
  时不处理。归档后普通列表、详情和导出立即不可访问。

- 用户删除 Research 只写 `deleted_at/deleted_by/deletion_reason`，立即从普通列表、详情和导出隐藏。
- Research、Step/Attempt、Source Snapshot、Evidence、Claim/Link、Report Version、LLM call、审计和
  outbox 不自动物理删除；外键与不可变守卫保护历史复现。
- Redis Provider cache 默认六小时，最大七天；Redis 可随时清空，不承担保留义务。
- 幂等记录至少保留 24 小时；只清理过期且不在处理中的记录。
- 不存完整 Prompt、Evidence Pack、模型原始错误 body、Authorization 或 Provider key，只存安全结构、
  hash、bytes、版本、usage、费用和稳定错误码。
- 导出缓存与不可变报告版本绑定；模板版本变化生成新缓存，不覆盖旧报告内容。

## 删除与恢复边界

R3 是项目负责人的个人研究选择，不宣称满足特定司法辖区或受监管行业的强制销毁期限。应用层采用
软归档以保护不可变 Evidence 审计；备份文件按 90 天实际物理删除。未来需要账户彻底销毁时，必须先
解除 legal hold、等待所有 90 天备份到期，再执行单独审批的离线物理清理流程。

未来物理清理必须是 owner-scoped、可审计、幂等的后台流程，并按外键依赖顺序执行；已处于 legal
hold 或被已发布报告引用的 Source/Evidence 不得清理。首次启用前必须在恢复副本验证旧报告 hash、
Evidence lineage、费用审计和删除证明。
