# AI Quant Research Assistant

面向美股与 ETF 的证据驱动研究平台。系统把研究问题拆解为取数、确定性计算、Evidence 注册、Claim 验证和报告发布步骤，目标是生成可复现、可追溯、会明确说明限制的研究辅助材料，而不是交易信号或收益承诺。

> 当前进度：Phase 0–6 已实现，Gate G6 正在进行最终 CI 终验。Responses API、严格 Structured Outputs、Evidence 工具白名单、输入/调用/成本预算和脱敏审计已接入；无模型配置时仍运行确定性 Mock 闭环。当前业务数据仍是固定演示数据，不产生真实或当前市场结论。

![Phase 1 research workspace](docs/assets/screenshots/phase1-workspace.png)

## 已确定的产品与工程边界

- 先完成 `MOCK` 纵向闭环，固定支持 MU、NVDA、RKLB；所有页面和导出必须显示 `DEMO DATA — NOT REAL MARKET DATA`。
- 价格、指标和情景结果只能来自来源数据或版本化的确定性计算；LLM 不负责创造金融数字。
- 重要结论使用 `FACT | CALCULATION | INFERENCE | OPINION` 分类，并关联同一研究任务中的 Evidence。
- PostgreSQL 是任务、lease、状态、取消、报告版本和审计的权威来源；Redis 只做可丢失的缓存与加速。
- Java 负责 API、权限、编排和最终发布；Python 负责量化分析；Next.js 只通过 Java API 访问业务能力。
- 真实行情和基本面 Provider 在 Phase 7 前完成价格、限流、存储、展示、导出和再分发许可评审后再选择。

## 技术栈

| 模块 | 技术 | 当前能力 |
| --- | --- | --- |
| `apps/web` | Next.js 16、React 19、TypeScript、Tailwind、TanStack Query、Zod | 研究创建、2 秒状态轮询、报告/Evidence/情景、Filing Chunk 检索、三种导出与历史重开的 Mock 闭环 |
| `apps/api` | Java 21、Spring Boot 3.5、Spring Security、JPA、Flyway、Redis、Resilience4j、OpenAI Responses API | Research API、durable Worker、Mock Provider、确定性 Evidence/Claim 校验与修复、Filing 检索、Mock/Real LLM 路由、调用预算与审计、报告原子发布/版本/导出 |
| `apps/analytics` | Python 3.12、FastAPI、Pydantic、Ruff、mypy、pytest | 版本化无状态分析 API：73 个收益/风险/技术/基本面/估值/情景 Metric 与可解释 Trend |
| 基础设施 | PostgreSQL 17、Redis 7.4、Docker Compose、GitHub Actions | 本地五服务编排与 CI 定义 |

完整系统设计见[架构基线](docs/architecture.md)，机器可读接口见 [OpenAPI 3.1](docs/openapi.yaml)，分阶段 Gate 见[实施计划](docs/implementation-plan.md)。

## 快速启动

推荐使用 Docker Desktop 或其他兼容 Docker Compose 的运行时：

```bash
cp .env.example .env
docker compose up --build
```

启动后可访问：

- Web：<http://localhost:3000>
- Web health：<http://localhost:3000/api/health>
- Java API health：<http://localhost:8080/api/v1/health>
- Analytics health：<http://localhost:8000/analytics/v1/health>
- Prometheus metrics：<http://localhost:8080/actuator/prometheus>

也可使用：

```bash
make dev-up
make dev-down
```

`make dev-up` 会在容器启动后自动执行 smoke。首次启动前请修改 `.env` 中的 demo 密码。本地 Compose 只把应用端口绑定到 `127.0.0.1`；dev-demo 用户只允许在 `development`/`test` profile 启用，不能作为生产认证方案。

## 本地开发与验证

前置环境：Node.js 24+、pnpm 11、Python 3.12、Java 21。Maven wrapper 已包含在 API 目录。

```bash
pnpm install --frozen-lockfile
python3.12 -m venv apps/analytics/.venv
apps/analytics/.venv/bin/pip install -e 'apps/analytics[dev]'

make lint
make typecheck
make test
make build
pnpm e2e:web
```

单独启动 Web：

```bash
pnpm dev:web
```

当前 Phase 6 本地基线：Web 与 Analytics 的既有 Gate 保持不变；API 167 个 Surefire 测试通过，新增 Failsafe/Testcontainers 用例已编译并交由 GitHub Actions 的 PostgreSQL 17 环境执行。新增覆盖 Responses HTTP mock、严格 Schema、`store=false`、只读工具循环、Prompt Injection、429/非法输出、输入上限、版本化计价、并发预算预留、真实网络调用计数和失败审计。详见 [Phase 6 Gate 测试矩阵](docs/phase6-test-matrix.md)。

当前尚未接入真实市场/基本面 Provider，也未在测试或 CI 中发送真实 OpenAI 请求。Phase 6 的真实 Adapter 由本地 HTTP mock 验证；部署只有同时提供 API Key、模型、HMAC secret 和带生效日期的价格版本时才会启用。成功终态仍必须与通过验证的不可变报告和运行 manifest 同事务发布。

## 数据与模型配置

默认 `DATA_MODE=MOCK`。允许的规范模式只有：

- `MOCK`：固定演示数据，必须持续显示 Demo 标记；
- `REAL`：只允许使用已批准的真实 Provider，不得静默混入 Mock；
- `MIXED_TEST`：仅自动化集成测试使用，不得生成普通用户报告或导出。

缺少 `OPENAI_API_KEY` 与 `OPENAI_REPORT_MODEL` 时，报告由确定性 Mock 生成器完成；只配置其中一个会失败关闭。真实模式采用 Responses API、严格 JSON Schema、`store=false`、`parallel_tool_calls=false`、HMAC `safety_identifier`、输入/输出/工具轮次上限和数据库预算预留。价格未知时不允许真实调用，不会伪造成本。

## 文档入口

- [可执行需求与 Must/Should/Could](docs/requirements.md)
- [架构、数据流与状态机](docs/architecture.md)
- [API 约定](docs/api.md)
- [数据模型](docs/data-model.md)
- [量化计算口径](docs/calculation-methodology.md)
- [LLM、Claim 与 Evidence 设计](docs/llm-design.md)
- [数据源与许可门禁](docs/data-sources.md)
- [安全与风险登记](docs/security.md)
- [实时进度](docs/progress.md)

## 下一步

Gate G6 终验通过后进入 Phase 7，按 SEC → FRED → 经许可的 Market → Fundamental 顺序接入真实 Provider。任何行情或基本面 Provider 都必须先通过持久化、缓存、展示、导出和再分发许可门禁；REAL 任务不得静默混入 Mock 数据。

## 免责声明

本项目只用于研究辅助与产品开发，不构成投资建议，不执行交易，也不承诺任何投资结果。Mock 数据不得被解释为真实或当前市场数据。
