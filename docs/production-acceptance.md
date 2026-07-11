# REAL 生产验收标准

最后更新：2026-07-11

首次上线使用 `evals/production-matrix.json` 的笛卡尔积：MU/NVDA/RKLB × 中英文 × 五种周期/深度场景，
共 30 个真实研究。评测只在私有云和已配置预算的情况下人工触发，CI 只验证矩阵结构，不调用外部服务。

通过条件：

- 30/30 报告满足 `research_report_v1` 严格 Schema；
- 重要 Claim 的 Evidence 关联率与 numeric reference 精确匹配率均为 100%；
- unsupported material claim 为 0，任何证据不足内容必须省略或显示 limitation；
- 至少 28/30 安全到达 `COMPLETED` 或 `PARTIALLY_COMPLETED`，不允许通过 Mock 回退形成 REAL 成功；
- 单任务实际 LLM 成本不超过 `$1.00`，30 个任务的 p95 完成时间不超过 20 分钟；
- Tiingo/SEC/FRED/LanYi Key 不出现在 Source URL、报告、日志、错误、Prometheus 或导出；
- 随机抽取 3 个报告完成重新打开、三格式导出 hash、Evidence lineage 与数据库重启恢复；
- 加密备份完成一次隔离解密、checksum 和 `pg_restore --list` 演练。

如果某一硬条件失败，不调整阈值制造通过；修复实现、Provider 配置或 Prompt 后生成新的报告版本并重跑
整个矩阵。模型、Prompt、Schema、价格或 Provider policy 任一版本改变，都需要重新验收。
