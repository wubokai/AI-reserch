# Phase 3 Gate 测试矩阵

状态：Phase 3 实现完成，本地闭环已验证；Gate G3 远程 CI 终验待通过

日期：2026-07-10

> 本文档严格区分“实现/本地验证完成”与“Gate G3 已通过”。只有 GitHub Actions Linux runner 上的 API Testcontainers、全服务 Compose smoke、Web、Analytics 和 secret scan 全绿后，才能将 Gate 状态改为“已通过”并补充远程 run 链接。

## 1. Phase 3 可执行边界

| 维度 | Phase 3 支持 | 边界断言 |
| --- | --- | --- |
| 数据模式 | 普通流程只允许 `MOCK` | 不需要真实 Provider Key 或 `OPENAI_API_KEY`；`MIXED_TEST` 不得普通发布/导出 |
| 目标证券 | `MU`、`NVDA`、`RKLB` | 其他 symbol，包括基准 ETF，在创建前返回 `400 INVALID_REQUEST` |
| 基准 | `SPY`、`QQQ` | 其他 benchmark 在创建前被拒绝 |
| 周期/深度 | `5y` / `STANDARD` | 显式日期区间、其他 period 或 QUICK/DEEP 被拒绝 |
| 技术分析 | 必须为 `true` | 省略时默认 true；false 在创建前被拒绝 |
| 基本面开关 | true 为完整模块；false 只跳过可选叙事 | 情景计算仍需规范化基本面数据；false 生成安全 `PARTIALLY_COMPLETED` / `PASSED_WITH_WARNINGS` 报告 |
| 宏观开关 | true 获取固定宏观 fixture；false 跳过该步骤 | 其他必需数据完整时仍可 `COMPLETED` / `PASSED` |

## 2. 分层验证基线

| 层 | 本地结果 | 主要覆盖 |
| --- | --- | --- |
| Web 单元/构建 | ESLint、TypeScript、20 个 Vitest、production build 通过 | BFF 契约/响应头、Zod Schema、Demo 标记、固定表单边界、终态轮询 |
| Web E2E | 4 个 Playwright 用例通过 | 创建、进度、报告/Evidence、情景、三种导出和历史重开 |
| API 单元/切片 | 139 个 Surefire 测试通过 | Mock Provider、Analytics 契约、Worker、报告/验证器、导出、Artifact API、创建边界 |
| PostgreSQL | 本地 PostgreSQL 17 的 Flyway V1–V6 和真实服务链路通过 | 血缘/不可变约束、跨任务 Evidence 阻断、`MIXED_TEST` 发布/导出阻断、原子步骤推进 |
| Analytics | Ruff、strict mypy、23 个 pytest 通过，覆盖率 91.29% | 数据校验、收益/风险/技术/基本面指标、Bull/Base/Bear 情景和版本化响应 |
| 报告/PDF 视觉 QA | 实际中文 API PDF 逐页检查通过 | A4 三页、中文字形、Demo 水印、来源与免责声明；无裁切/重叠，关键文本可抽取 |

## 3. 能力与测试载体

| 能力 | 关键场景与断言 | 主要测试载体 |
| --- | --- | --- |
| 固定 Provider | MU/NVDA/RKLB 与 SPY/QQQ fixture 规范化、有序、内容哈希稳定；不支持证券快速失败 | `MockProvidersTest` |
| 跨语言 Analytics | Java 构造版本化 full-analysis 请求；Python 返回确定性指标和三情景 | `AnalyticsRequestFactoryTest`、`test_analysis_contract.py`、`test_core_metrics.py`、`test_fundamentals_scenarios.py` |
| Analytics 失败分类 | HTTP 503/超时可重试；Schema 漂移不可重试；错误不泄露响应或凭据 | `HttpAnalyticsClientTest`、`ResearchWorkerRuntimeTest` |
| Worker lease | 心跳成功续租；连续失败后自隔离；恢复后重置失败计数；旧 lease 不可提交 | `LeaseHeartbeatSchedulerTest`、`ResearchWorkerRuntimeTest`、`DurableQueueIT` |
| 原子步骤推进 | 成功输出哈希派生唯一后继输入；跳过的可选步骤可投影；旧 Worker 无法绕过 fencing | `Phase3ArtifactsIT`、`ResearchWorkflowServiceTest` |
| Evidence/Claim | Evidence 与 Claim 只能关联同一 Research；发布后不可改；material Claim 需 Evidence；数字/单位/日期反向验证 | `Phase3ArtifactsIT`、`ReportValidatorTest`、`DeterministicMockReportGeneratorTest` |
| 失败关闭 | Evidence 为空、必需情景缺失、未知 Evidence ID 或数字不匹配时不发布“成功”报告 | `DeterministicMockReportGeneratorTest`、`ReportValidatorTest`、`ResearchWorkerRuntimeTest` |
| 报告发布 | 验证状态、不可变 ReportVersion、Claim/Link、run manifest 和 Research 终态同事务发布 | `Phase3ArtifactsIT`、`ResearchJobConstraintsIT` |
| Artifact API | Evidence 过滤/反向 Claim、报告版本、证券搜索、Provider 状态和 owner scope | `ArtifactApiControllerTest`、`ArtifactQueryServiceTest`、`ResearchHttpLifecycleIT` |
| 确定性导出 | Markdown/HTML/PDF 内容、MIME、文件名、ETag/SHA-256/版本/数据模式一致；导出缓存按渲染版本隔离 | `ReportExportServiceTest`、`ReportExportStoreTest`、`ArtifactApiControllerTest` |
| HTML/PDF 安全 | HTML 转义脚本/链接输入，无外部资源；PDF 使用本地 Noto Sans SC，中文可抽取 | `ReportRenderersTest` |
| Web 闭环 | BFF 不向浏览器暴露 API 凭据；2 秒轮询在终态停止；三种下载保留安全响应头 | `api-proxy.test.ts`、`research-progress.test.ts`、`closed-loop.spec.ts` |

## 4. 本地真实闭环证据

使用 Java 21、本地 PostgreSQL 17、真实 Java API 进程和真实 Python Analytics 进程执行，不用 Controller/Repository mock 代替服务链路。

| 场景 | 结果 |
| --- | --- |
| MU/NVDA/RKLB 完整请求 | 均 `COMPLETED` / `PASSED`，每份报告包含 5 个主要章节和 Bull/Base/Bear 三情景 |
| MU + `includeFundamentalAnalysis=false` | `PARTIALLY_COMPLETED` / `PASSED_WITH_WARNINGS`，明确记录基本面叙事 `NOT_AVAILABLE (not requested)`，三情景仍存在 |
| MU + `includeMacroAnalysis=false` | `COMPLETED` / `PASSED`，只有 `FETCH_MACRO_DATA` 为计划内 `SKIPPED` |
| 五个上述任务的血缘审计 | 5 个 report/manifest、84 个 Claim 和 84 个 Claim-Evidence Link、149 条 Evidence、120 条 Quant Result；unsupported material Claim 为 0 |
| 边界拒绝 | 把 SPY 作为目标或传 `includeTechnicalAnalysis=false` 均返回 `400 INVALID_REQUEST`，未创建 Research |
| 后段故障与手动重试 | 停止 Analytics 后任务在 `RUN_QUANT_ANALYSIS` 三次尝试后失败；恢复服务并重试后，步骤 1–6 仍只执行 1 次，量化步骤累计第 4 次成功，后续步骤各执行 1 次，报告和 manifest 发布 |
| 量化可重现 | 正常 MU 与故障恢复 MU 的 24 项指标值/单位差异数均为 0 |
| 导出 | 三种格式均返回正确内容类型、文件名、ETag、SHA-256、报告版本和 `MOCK` 模式；重复 PDF 字节完全相同 |
| Web BFF 实际转发 | 历史查询成功；BFF 转发的 PDF 字节与 API 完全一致，并保留 `X-Content-SHA256` 等安全响应头 |

## 5. Gate G3 远程通过条件

当前未通过的不是业务实现项，而是缺少最终远程环境证据。远程必须同时满足：

1. Web lint/typecheck/unit/build/Playwright 全绿；
2. API Surefire 与所有 Failsafe/Testcontainers 用例全绿，无跳过的 `*IT`；
3. Analytics Ruff/format/strict mypy/pytest 全绿；
4. Docker Compose 构建并启动 Web、API、Analytics、PostgreSQL、Redis，然后以真实 HTTP 完成 MU 创建、终态、Evidence、报告、三种导出和历史检查；
5. 容器中的 PDF 保持 Demo 水印、中文字体和本地资源边界；
6. secret scan 通过，无真实 Provider/OpenAI Key 依赖。

全部通过后，在本文档、[`progress.md`](./progress.md) 和 [`risk-register.md`](./risk-register.md) 记录同一 GitHub Actions run，再将 Gate G3 标记为已通过。

## 6. 受控限制

- 没有真实行情、基本面、SEC、宏观或新闻 Provider；所有金融数据都是固定 Mock fixture。
- 没有真实 LLM；Phase 3 的报告叙事是确定性 Mock 输出。
- 只支持固定三个美股目标、两个基准、5y 和 STANDARD；这是 MVP 边界，不是泛化市场数据能力。
- Phase 3 验证器为安全闭环基线；更完整的 Freshness、语义支持、SEC 检索与受约束修复仍属于 Phase 5。
