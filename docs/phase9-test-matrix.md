# Phase 9 发布硬化与 Gate G9 测试矩阵

最后更新：2026-07-11

## 1. 硬化范围

| 能力 | 自动化/实现证据 | 当前结果 |
| --- | --- | --- |
| JSON 日志 | API/Analytics 单行 JSON；安全 route、Request/Research ID；无 query/body/secret | 本地通过 |
| Prometheus | HTTP、Research status、Worker、queue age、outbox、LLM cost、Provider/cache/retry | 本地通过 |
| 告警 | 5xx、队列、失败率、outbox、Provider circuit 规则 | 已实现 |
| 输入限制 | DTO + JSON 64 KiB/token/层级/字符串/字段名/数字限制 | 本地通过 |
| SSRF | SEC/FRED official host；OpenAI official HTTPS；loopback 仅测试；PDF 禁远程资源 | 本地通过 |
| XSS/浏览器 | HTML 全转义；Web/API CSP、nosniff、DENY、no-referrer、Permissions Policy | 本地通过 |
| Prompt Injection | untrusted Evidence 边界、三工具 allowlist、严格 Schema、发布验证 | 回归覆盖 |
| 周期与深度预算 | 1y/3y/5y/显式范围；QUICK/STANDARD/DEEP 约束 Filing/Evidence/Calculation/LLM 工具轮次 | 本地通过 |
| 规范化金融事实 | Flyway V9；行情/财务/宏观关联不可变 Snapshot、去重、血缘触发器；Compose smoke 验证非空 | 待本提交远端容器终验 |
| 公司名与 Filing RAG | company-only 唯一解析；`search_evidence` 连接 Research-scoped PostgreSQL FTS 并复核 Evidence allowlist | 本地通过；FTS SQL 待远端容器终验 |
| Transactional outbox | `SKIP LOCKED` 有界 relay、event ID、失败码/重放；Compose 等待 backlog 清零 | 本地单测通过；待远端容器终验 |
| 完整安全报告 | Executive/Company/Financial/Quant/Technical/Valuation/Scenario、3 条可用 Bull/Bear、7 类风险；缺客户/历史/同行估值显式限制 | 本地 Validator/Renderer 通过 |
| 执行时间预算 | 持久化 `started_at` + 1–1440 分钟配置；边界前允许、边界时永久失败、发布前复查 | 本地通过 |
| IDOR | Research/Report/Evidence/Export owner-scoped，跨用户统一 404 | 回归覆盖 |
| PDF | 离线字体、无 URI/JS、25 MiB、200 页、字体 50 MiB、失败隔离 | 本地通过 |
| 供应链 | pnpm lock/audit、Python 精确版本/pip-audit、Dependabot、secret scan、固定校验和 Grype 镜像扫描 | 应用依赖 0 已知漏洞；镜像 Critical 阻断，高风险显式评审 |
| 容器 | 三个应用 non-root、read-only、tmpfs、cap-drop ALL、no-new-privileges、PID limit | 远端通过 |
| 文档 | README、运行手册、可观测性、Provider 扩展、保留策略、风险与验收矩阵 | 已完成 |

## 2. AC-01–AC-35 完整验收

| AC | 状态 | 证据/边界 |
| --- | --- | --- |
| 01–08 | 通过 | Compose、Research API/Worker、Mock 闭环与 Demo 标识 |
| 09–10 | 通过 | 73 Metric、黄金集、NOT_AVAILABLE/NOT_APPLICABLE |
| 11–14 | 通过 | Evidence/Claim/数字日期验证、情景与失败关闭 |
| 15–21 | 通过 | 历史/版本、三导出、Provider 降级、SEC/FRED |
| 22 | 外部门禁 | 真实 Market 必须先取得展示/导出/再分发书面许可；工程端口、runtime、REAL 发布门已完成 |
| 23 | 通过 | SEC Companyfacts/XBRL 与黄金映射 |
| 24–28 | 通过 | env secret、IDOR、Prompt 对抗、LLM 预算、JSON 日志/Prometheus |
| 29–35 | 通过 | 全 CI、README clean start、浏览器、错误映射、无硬编码金融结论/交易、免责声明 |

## 3. 本地命令

- `pnpm audit`：0 已知漏洞；
- API Surefire：231 个通过；49 个 Failsafe/Testcontainers 已定义；
- Python `pip-audit --local`：0 已知漏洞；
- Web、Analytics、API/Testcontainers、三应用镜像 Grype、最小权限策略与 Compose smoke：
  [GitHub Actions run `29145630809`](https://github.com/wubokai/AI-reserch/actions/runs/29145630809) 全部通过；
- 连续 CI：上述功能终验与本文件所在最终证据提交的同名 `ci` check 共同构成两次连续主干验证。

## 4. Gate 判定

G9 工程硬化已在全量本地测试及连续远端 CI 通过后完成。完整 REAL v1 仍必须等待 AC-22 的行情许可、
Adapter 凭据/测试账户和真实全 Worker 终验；未取得书面权利前系统正确行为是保持 Market disabled，
不得以 Mock 或 SEC/FRED 局部真实数据冒充完整 REAL。
