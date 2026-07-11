# 数据保留与删除策略

最后更新：2026-07-11

## 当前 v1 行为

- 用户删除 Research 只写 `deleted_at/deleted_by/deletion_reason`，立即从普通列表、详情和导出隐藏。
- Research、Step/Attempt、Source Snapshot、Evidence、Claim/Link、Report Version、LLM call、审计和
  outbox 不自动物理删除；外键与不可变守卫保护历史复现。
- Redis Provider cache 默认六小时，最大七天；Redis 可随时清空，不承担保留义务。
- 幂等记录至少保留 24 小时；只清理过期且不在处理中的记录。
- 不存完整 Prompt、Evidence Pack、模型原始错误 body、Authorization 或 Provider key，只存安全结构、
  hash、bytes、版本、usage、费用和稳定错误码。
- 导出缓存与不可变报告版本绑定；模板版本变化生成新缓存，不覆盖旧报告内容。

## 生产策略门

上线前由数据/合规负责人为 Research artifact、审计、LLM usage、Provider raw payload 和备份分别给出
书面期限、合法依据、legal hold、导出/删除 SLA 和销毁验证。没有该决定时保持“不自动物理删除”的
保守行为，不能声称满足特定司法辖区或行业的法定期限。

未来物理清理必须是 owner-scoped、可审计、幂等的后台流程，并按外键依赖顺序执行；已处于 legal
hold 或被已发布报告引用的 Source/Evidence 不得清理。首次启用前必须在恢复副本验证旧报告 hash、
Evidence lineage、费用审计和删除证明。
