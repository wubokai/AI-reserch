# Phase 7 真实数据源与 Gate G7 测试矩阵

最后更新：2026-07-10

## 1. 当前范围

Phase 7 按 SEC → FRED → Market → Fundamental 分段交付。本文件当前记录 SEC EDGAR
首检查点；它尚不代表 Gate G7 完成，也不启用完整 REAL 用户闭环。

已实现：

- `FilingProvider` 保持领域端口，SEC DTO、URL、认证头和错误分类仅存在于 Adapter；
- SEC ticker → CIK → submissions → allowlisted primary document 映射；
- 官方 HTTPS 主机边界、声明式 User-Agent、每实例全局速率控制、超时、响应体与路径上限；
- 429/502/503、网络和超时的有限重试；其他 HTTP、内容类型、Schema 和不安全文档永久失败；
- 原始多响应字节哈希、规范化哈希、retrieved/effective date、freshness、来源 URL 和访问政策版本；
- REAL Source Snapshot、research link、Filing Registry 与 `raw_text_uri` 的不可变落库链路；
- `DATA_MODE=MOCK` 启用 SEC 时启动失败，禁止真实/Mock 静默混合。

## 2. SEC 首检查点验收矩阵

| 要求 | 自动化证据 | 结果 |
| --- | --- | --- |
| 正常映射 | loopback HTTP fixture 验证 ticker、CIK、10-K、报告期、摘要和官方文档 URL | 通过 |
| 合规身份 | 每次请求断言同一声明式 User-Agent；空或无联系邮箱配置失败关闭 | 通过 |
| Rate Limit | 配置约束限制为 1–10 次/秒，单 Adapter governor 使用单调时钟串行发放请求 | 通过 |
| Retry | 429 首次失败、第二次成功；最大尝试次数 1–4 | 通过 |
| 内容与体积边界 | JSON/HTML 内容类型检查、空体和 WebClient/显式字节上限 | 通过 |
| SSRF/路径安全 | 正式配置只允许 SEC 官方 HTTPS 主机；`../escape.htm` 在 archive 请求前拒绝 | 通过 |
| Schema 快速失败 | 数组缺失、索引长度不一致、非法日期/CIK/文档标识均为稳定永久错误 | 通过 |
| Raw lineage | 原始响应按长度分隔后 SHA-256，规范化 JSON 使用独立哈希 | 通过 |
| 数据模式隔离 | Runtime Validator 拒绝 `MOCK + filing=sec` | 通过 |
| 持久化来源 | Testcontainers 验证 SEC provider/type/URL/raw hash/freshness/policy 与 Filing `raw_text_uri` | 通过 |
| 既有闭环无回归 | API 175 个 Surefire 全通过；Mock Filing 兼容构造与默认 provider 保持不变 | 通过 |

## 3. 本地验证

- Eclipse Temurin Java 21.0.11；
- `./mvnw test`：175 个 Surefire，0 失败；
- `./mvnw -DskipTests verify`：生产与 56 个测试源编译、打包成功；
- `git diff --check` 在提交前执行；
- 本机无 Docker，45 个 Failsafe/Testcontainers 与五服务 Compose 由 GitHub Actions
  Linux runner 完成最终验证；所有 SEC Contract Test 只访问 loopback，不访问 SEC 外网。

## 4. GitHub Actions 验证

- 首次检查点 [run `29134022515`](https://github.com/wubokai/AI-reserch/actions/runs/29134022515)：
  Web、Analytics、secret scan、175 个 Surefire 和原有 44 个 Failsafe 均通过；新增 lineage
  测试 fixture 错误组合了 `QUEUED / 0% / FETCH_FILINGS`，被既有生命周期投影约束正确拒绝。
  修复只把 fixture 改为合法的 `QUEUED / 0% / RESOLVE_SECURITY`，没有修改生产约束；
- 最终检查点 [run `29134112081`](https://github.com/wubokai/AI-reserch/actions/runs/29134112081)：
  Web/Playwright、Analytics、175 个 Surefire、45 个 Failsafe/Testcontainers、secret scan
  与五服务 Compose 全部通过；SEC 真实来源持久化测试和原有 Mock 闭环均在最终 head 验证。

## 5. 尚未关闭的 Gate G7 项

- SEC Redis 缓存、熔断器、Provider 状态/指标和完整 REAL Worker 编排；
- FRED observation/vintage、归属声明、修订与许可边界；
- Market/Fundamental 功能、质量、成本以及存储/展示/导出/再分发许可决策；
- 所选 Market/Fundamental Adapter、全 REAL 报告、UI 归属和导出验证；
- 最终 Web、Analytics、API/Testcontainers、secret scan 与 Compose 全绿。

因此当前状态为“Phase 7 进行中 / SEC 首检查点”，不是 Gate G7 通过。
