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
| IDOR | Research/Report/Evidence/Export owner-scoped，跨用户统一 404 | 回归覆盖 |
| PDF | 离线字体、无 URI/JS、25 MiB、200 页、字体 50 MiB、失败隔离 | 本地通过 |
| 供应链 | pnpm lock/audit、Python 精确版本/pip-audit、Dependabot、secret scan、固定校验和 Grype 镜像扫描 | 本地依赖通过，镜像 CI 待终验 |
| 容器 | 三个应用 non-root、read-only、tmpfs、cap-drop ALL、no-new-privileges、PID limit | CI 待终验 |
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
| 29–35 | CI 待终验 | 全 CI、README clean start、浏览器、错误映射、无硬编码金融结论/交易、免责声明 |

## 3. 本地命令

- `pnpm audit`：0 已知漏洞；
- API Surefire：208 个通过；
- Python `pip-audit --local`：0 已知漏洞；
- Web、Analytics、API 全量与 Compose 结果将在本阶段最终 CI 记录。

## 4. Gate 判定

G9 工程硬化在全量本地测试及连续远端 CI 通过后完成。完整 REAL v1 仍必须等待 AC-22 的行情许可、
Adapter 凭据/测试账户和真实全 Worker 终验；未取得书面权利前系统正确行为是保持 Market disabled，
不得以 Mock 或 SEC/FRED 局部真实数据冒充完整 REAL。
