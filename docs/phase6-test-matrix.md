# Phase 6 Responses API、LLM 预算安全与 Gate G6 测试矩阵

最后更新：2026-07-10

## 1. 实现边界

Phase 6 在 Gate G5 的 Evidence/Claim 发布门禁之上增加真实模型 Adapter，但不在测试、
CI 或默认开发环境调用真实外部模型：

- `ResearchLanguageModel` 端口包含确定性 Mock 与 OpenAI Responses 双实现；API Key 和模型
  都为空时使用 Mock，只配置其中一个时失败关闭；
- Responses 请求使用 `store=false`、`parallel_tool_calls=false`、HMAC
  `safety_identifier`、版本化 `prompt_cache_key` 和 Strict Structured Outputs；
- 六个 LLM Schema 覆盖 Research Plan、Filing Analysis、Fundamental Narrative、Risk、
  Final Report 与 Validation；OpenAI 子集规范化不削弱领域 Schema；
- 模型只能调用当前 Research 的 `search_evidence`、`get_evidence`、
  `get_calculation`，每轮最多一个调用，轮次有硬上限；
- Evidence Pack 使用规范化 JSON 和 `UNTRUSTED_EXTERNAL_DATA` 边界；超过
  `OPENAI_MAX_INPUT_BYTES` 时在预算/网络前拒绝；
- Flyway V8 新增版本化价格、provider request ID、真实 HTTP 调用计数和
  `llm_budget_reservations`；成本与调用数均先预留后结算；
- 真实调用失败写入脱敏 `FAILED | REFUSED | INCOMPLETE` 审计；安全回退生成独立 Mock
  审计，并继续通过原有完整 Validator 与最多一次确定性修复。

## 2. 验收矩阵

| Gate 要求 | 自动化证据 | 结果 |
| --- | --- | --- |
| Mock/Real 边界明确 | Router 测试覆盖全空走 Mock、部分配置失败、真实失败的显式安全回退 | 通过 |
| Strict Structured Outputs | HTTP mock 断言 `text.format.type=json_schema`、`strict=true`、闭合对象和全部 required；6 个 Schema 可加载 | 通过 |
| 无服务端响应存储 | HTTP mock 断言每个请求 `store=false` | 通过 |
| 身份与缓存键脱敏 | HMAC `safety_identifier` 不含 owner UUID；cache key 只含版本；请求日志不保存 Authorization/Prompt | 通过 |
| Prompt Injection 隔离 | 恶意 Evidence 指令只出现在 `UNTRUSTED_EXTERNAL_DATA`；工具列表保持固定三项且未知工具失败 | 通过 |
| 工具调用安全 | `parallel_tool_calls=false`；每轮只允许一个调用；Evidence/Calculation 必须在当前请求 allowlist；轮次有上限 | 通过 |
| 输入/输出边界 | Evidence Pack/完整首轮请求 byte 上限网络前拒绝；工具输出有独立 byte 上限；output token 与 Schema 上限同时生效 | 通过 |
| 版本化价格 | 单测覆盖输入、缓存输入、输出成本；价格版本/生效日/费率缺失时真实调用失败关闭 | 通过 |
| 并发成本预算 | V8 + Testcontainers 锁定 Research，聚合已花费与活跃预留，超预算不发网络；预留身份不可变 | 远程终验中 |
| 多轮调用预算 | 按 `maxToolRounds + 1` 预留真实 HTTP 次数及上下文增长成本，按实际 `networkCallCount` 结算 | 远程终验中 |
| 成功/失败审计 | 成功记录 request/response hash、usage、价格/Prompt/Schema 版本、延迟和 provider ID；失败不存 Prompt/body | 远程终验中 |
| 拒绝与故障分类 | HTTP mock 覆盖 429 可重试、非法结构不可重试；Adapter 独立映射 refusal、incomplete、502/503、网络/超时 | 通过 |
| 最终总结安全降级 | Router 测试验证失败码与 `LLM_FINAL_SUMMARY_FAILED_SAFE_FALLBACK` warning；回退报告仍走发布验证 | 通过 |
| 既有闭环无回归 | Web、Analytics、API、secret scan 与五服务 Compose 使用无 Key 的 Mock 路径 | 远程终验中 |

## 3. 验证结果

### 本地

- API：Java 21，167 个 Surefire 单元测试通过；全部 Failsafe 测试可编译；
- Schema：6 个 LLM JSON Schema 全部可解析并由 classpath catalog 加载；
- `git diff --check` 通过；本机无 Docker，因此 PostgreSQL/Testcontainers 与 Compose
  继续以 GitHub Actions Linux runner 为最终依据；
- 测试仅访问本地 HTTP mock，不读取真实 `OPENAI_API_KEY`，不产生外部模型费用。

### GitHub Actions

- 第一检查点 [run 29117526084](https://github.com/wubokai/AI-reserch/actions/runs/29117526084)：
  Web、Analytics 与 secret scan 通过；API 暴露 V8 不可变触发器函数名拼写错误，迁移事务
  完整回滚，Compose 按依赖正确跳过；修复未通过删除或弱化测试；
- 第二检查点 [run 29117946214](https://github.com/wubokai/AI-reserch/actions/runs/29117946214)：
  V8 已成功迁移；Phase 6 新集成测试的手写 Research fixture 使用了不合法的
  `RUNNING/80/GENERATE_REPORT` 组合，被既有 V4 生命周期约束正确拒绝。Fixture 已改为
  `GENERATING_REPORT/90/GENERATE_REPORT`，没有修改或放宽生产约束；下一检查点继续终验。
- 第三检查点 [run 29118287703](https://github.com/wubokai/AI-reserch/actions/runs/29118287703)：
  167 个 Surefire 全通过，43/44 个 Failsafe 通过；唯一失败是失败审计测试构造了空
  Evidence allowlist，被 `ResearchLanguageModelRequest` 的生产不变量正确拒绝。测试已改为
  注册最小 Evidence fixture，没有弱化请求边界。

## 4. Gate G6 当前结论

实现项和本地测试已完成；Gate G6 只有在最终 head 的 Web、Analytics、API/Testcontainers、
secret scan 与 Compose 全绿后关闭。在此之前不得进入 Phase 7 的完整实现。

## 5. 受控限制

- CI 不调用真实 OpenAI；生产模型选择与质量评测需部署者提供模型 slug、Key、HMAC secret、
  单任务预算和当期官方价格版本；
- 当前用户闭环仍是 `MOCK` 数据；真实 LLM Adapter 已就绪不等于真实行情/基本面已就绪；
- 最终报告的首次失败修复仍由确定性安全修复器执行，不让第二次模型调用扩大 Evidence 或
  修改金融数字；若未来启用模型修复，必须作为新 Prompt/Schema 版本重新通过 Gate；
- 429/502/503 只被分类为可重试，实际退避/熔断仍由 Worker/Resilience4j 的外层执行策略负责；
- 真实 Provider 数据、交易日历、SEC/FRED 网络契约与许可属于 Phase 7。
