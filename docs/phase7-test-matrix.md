# Phase 7 真实数据源与 Gate G7 测试矩阵

最后更新：2026-07-11

## 1. 当前范围

Phase 7 按 SEC → FRED → Market → Fundamental 分段交付。本文件记录 SEC EDGAR、
FRED、SEC Companyfacts/XBRL 与统一 Provider Runtime 检查点；它们尚不代表 Gate G7
完成，也不启用完整 REAL 用户闭环。

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

## 3. FRED 本地检查点验收矩阵

| 要求 | 自动化证据 | 结果 |
| --- | --- | --- |
| Vintage 固定 | 请求断言 realtime start/end 等于固定抓取日，快照独立保存 vintage/effective date | 通过 |
| 元数据与修订 | 映射 frequency、units、seasonal adjustment、last updated 与 observation realtime 边界 | 通过 |
| 缺失与截断 | `.` 明确跳过；无有效观测、非法数值或 count 超过返回集合时失败关闭 | 通过 |
| Key 脱敏 | 401 fixture 在 body 中放入 key/secret，异常 message 和 cause 均不泄露 | 通过 |
| Retry | 429 首次失败、第二次成功，最大尝试次数有界 | 通过 |
| 归属与许可 | 快照包含 FRED 要求的 attribution 和条款复核版本 | 通过 |
| 数据模式隔离 | Runtime Validator 拒绝 `MOCK + macro=fred` | 通过 |
| 持久化来源 | Testcontainers 验证 FRED government source、raw hash、policy、attribution 且无 api_key | 通过 |

## 4. 本地验证

- Eclipse Temurin Java 21.0.11；
- `./mvnw test`：179 个 Surefire，0 失败；
- `./mvnw -DskipTests test-compile`：生产与 57 个测试源编译成功；
- `git diff --check` 在提交前执行；
- 本机无 Docker，45 个 Failsafe/Testcontainers 与五服务 Compose 由 GitHub Actions
  Linux runner 完成最终验证；所有 SEC Contract Test 只访问 loopback，不访问 SEC 外网。

## 5. GitHub Actions 验证

- 首次检查点 [run `29134022515`](https://github.com/wubokai/AI-reserch/actions/runs/29134022515)：
  Web、Analytics、secret scan、175 个 Surefire 和原有 44 个 Failsafe 均通过；新增 lineage
  测试 fixture 错误组合了 `QUEUED / 0% / FETCH_FILINGS`，被既有生命周期投影约束正确拒绝。
  修复只把 fixture 改为合法的 `QUEUED / 0% / RESOLVE_SECURITY`，没有修改生产约束；
- 最终检查点 [run `29134112081`](https://github.com/wubokai/AI-reserch/actions/runs/29134112081)：
  Web/Playwright、Analytics、175 个 Surefire、45 个 Failsafe/Testcontainers、secret scan
  与五服务 Compose 全部通过；SEC 真实来源持久化测试和原有 Mock 闭环均在最终 head 验证。
- FRED 检查点 [run `29134411188`](https://github.com/wubokai/AI-reserch/actions/runs/29134411188)：
  Web/Playwright、Analytics、179 个 Surefire、46 个 Failsafe/Testcontainers、secret scan
  与五服务 Compose 全部通过；FRED vintage/attribution/Key 脱敏、真实来源落库与默认 Mock
  无 Key 启动路径均在同一 head 验证。

## 6. 尚未关闭的 Gate G7 项

- 完整 REAL Worker 编排；
- Market 功能、质量、成本以及存储/展示/导出/再分发书面许可；
- 经许可的 Market Adapter、全 REAL 报告、UI 归属和导出验证。

因此当前状态为“Phase 7 进行中 / SEC、FRED、SEC XBRL、Provider Runtime 与多格式归属检查点已完成”，不是 Gate G7 通过。

## 7. Market/Fundamental 许可决策检查点

- Fundamental 选择 SEC Companyfacts/XBRL；Market 因公开许可不覆盖外部展示和报告导出而
  保持未选择，Twelve Data 仅作为取得书面 Redistribution 权利后的候选；
- Mock Market/Fundamental Bean 已改为显式配置条件，不会与未来真实 Adapter 同时注入；
- 真实 Market 配置缺少 `MARKET_DATA_LICENSE_CONFIRMED=true` 或版本化 policy 时启动失败；
- Runtime Boundary 新增 2 个测试，全量 API 181 个 Surefire 测试通过；
- 决策证据见 [`provider-license-matrix.md`](./provider-license-matrix.md) 与
  [`ADR-0009`](./adr/0009-phase7-provider-license-decision.md)。
- 全仓终验 [run `29138819196`](https://github.com/wubokai/AI-reserch/actions/runs/29138819196)：
  Web/Playwright、Analytics、181 个 Surefire、46 个 Failsafe/Testcontainers、secret scan
  与 Compose 全部通过。

## 8. SEC Companyfacts/XBRL 本地检查点

| 要求 | 自动化证据 | 结果 |
| --- | --- | --- |
| Company identity | ticker → CIK 与 Companyfacts `cik` 必须一致 | 通过 |
| 修订去重 | 同期 10-K 与更晚 10-K/A 从 1000 选择 1100 | 通过 |
| Time travel | retrieval date 之后 filed 的 9999 被排除 | 通过 |
| Period validity | FY 只接受 300–400 天和 `fp=FY` | 通过 |
| Unit validity | USD/shares 精确匹配，`val` 必须为 JSON number | 通过 |
| Cross-period | 不同 start/end 的 Gross Profit 与 Revenue 不生成 Gross Margin | 通过 |
| 手算派生 | Margin 0.4、FCF 220、EBITDA proxy 250、Net Debt 300 | 通过 |
| Lineage | 每项保留 taxonomy/concept/accession/filed/component concepts | 通过 |
| Freshness | mixed FY/quarterly period 产生 warning，按 FY coverage 评估 | 通过 |
| 数据模式 | `MOCK + fundamental=sec-xbrl` 启动失败关闭 | 通过 |
| 不可变落库 | Testcontainers 验证 SEC source type、concept 与 accession | 通过 |
| 全量回归 | API 185 个 Surefire，0 失败 | 通过 |

完整 grain、映射、质量风险和限制见 [`sec-xbrl-mapping.md`](./sec-xbrl-mapping.md)。

全仓终验 [run `29141192029`](https://github.com/wubokai/AI-reserch/actions/runs/29141192029)：
Web/Playwright、Analytics、185 个 Surefire、47 个 Failsafe/Testcontainers、secret scan
与 Compose 全部通过。

## 9. Provider Runtime 终验检查点

| 要求 | 自动化证据 | 结果 |
| --- | --- | --- |
| Cache key | subject 只以 SHA-256 出现在 key，不含原始 subject | 通过 |
| Cache hit | 第二次请求不执行 loader | 通过 |
| TTL | Testcontainers Redis 验证 6 小时以内的正 TTL | 通过 |
| Entry boundary | 超过 5 MB 配置上限时跳过缓存，不阻断 Provider | 通过 |
| Cache degradation | Redis 读取异常按 miss 继续并记录 error metric | 通过 |
| Circuit predicate | 两次可重试故障可打开测试电路 | 通过 |
| Permanent errors | 多次永久 Schema 错误后电路仍为 CLOSED | 通过 |
| Metrics | requests/cache/retries 仅使用受控低基数 tags | 通过 |
| Adapter integration | SEC Filing、SEC XBRL、FRED 均通过统一 Runtime | 通过 |
| 全量回归 | API 191 个 Surefire、48 个 Failsafe/Testcontainers，0 失败 | 通过 |

全仓终验 [run `29142394155`](https://github.com/wubokai/AI-reserch/actions/runs/29142394155)：
Web/Playwright、Analytics、191 个 Surefire、48 个 Failsafe/Testcontainers、secret scan
与五服务 Compose 全部通过。

## 10. SEC/FRED 来源归属本地检查点

| 要求 | 自动化证据 | 结果 |
| --- | --- | --- |
| 不可变来源 | 导出查询保留 Provider、官方 URL、归属文本和许可策略版本 | 通过 |
| Evidence API | OpenAPI 与 Web Zod 契约显式返回 nullable attribution/policy | 通过 |
| 页面展示 | REAL 报告显示去重后的官方来源、归属、policy 和安全外链 | 通过 |
| Markdown | 独立 Data source attribution 段包含 FRED/SEC、URL 和 policy | 通过 |
| HTML/PDF | 自包含 HTML 与 PDF 文本均包含 FRED/SEC 归属，不创建外部 PDF URI | 通过 |
| 模式隔离 | REAL 页面/导出不显示 Demo 标识；Mock 仍强制显示 | 通过 |
| 缓存失效 | 模板提升至 markdown-v2/html-v3/pdf-v3，不复用旧导出字节 | 通过 |
| 全量回归 | API 193+48、Web 21 个 Vitest、lint/typecheck/build/Playwright、Compose | 通过 |

全仓终验 [run `29143064626`](https://github.com/wubokai/AI-reserch/actions/runs/29143064626)：
Web/Playwright、Analytics、193 个 Surefire、48 个 Failsafe/Testcontainers、secret scan
与五服务 Compose 全部通过。
