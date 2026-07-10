# AI Quant Research Assistant

面向美股与 ETF 的证据驱动研究平台。系统把研究问题拆解为取数、确定性计算、Evidence 注册、Claim 验证和报告发布步骤，目标是生成可复现、可追溯、会明确说明限制的研究辅助材料，而不是交易信号或收益承诺。

> 当前进度：Phase 0–4 与 Gate G4 已完成。完整 `quant_v1` 量化、黄金数据和数值边界已通过本地与 [GitHub Actions run 29111976669](https://github.com/wubokai/AI-reserch/actions/runs/29111976669) 终验；原有 Mock 纵向闭环仍保持 `COMPLETED`。当前仅使用固定演示数据，不产生真实或当前市场结论。

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
| `apps/web` | Next.js 16、React 19、TypeScript、Tailwind、TanStack Query、Zod | 研究创建、2 秒状态轮询、报告/Evidence/情景、三种导出与历史重开的 Mock 闭环 |
| `apps/api` | Java 21、Spring Boot 3.5、Spring Security、JPA、Flyway、Redis、Resilience4j | Research API、PostgreSQL durable Worker、lease/fencing/reaper、幂等/重试、Mock Provider、Evidence/Claim、报告校验/原子发布、版本与导出 |
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

当前 Phase 4 验证基线：Web 的 ESLint、TypeScript、20 个 Vitest、production build 与 4 个 Playwright 用例通过；API 144 个 Surefire 与 38 个 Failsafe/Testcontainers 测试及 PostgreSQL 17/Flyway V1–V6 集成验证通过；Analytics 的 Ruff、strict mypy 与 41 个 pytest 通过，branch coverage 93.92%。Gate G4 的远程 Compose 终验还验证了五服务健康、幂等重放、3 次轮询后 `COMPLETED`、Evidence、报告 v1、历史、三格式导出、确定性 PDF 与 Web BFF 透传。详见 [Phase 4 Gate 测试矩阵](docs/phase4-test-matrix.md)。

当前尚未接入真实市场/基本面 Provider 或 OpenAI。Phase 3 使用版本化固定 fixture、确定性 Python 计算和确定性 Mock 报告生成器；成功终态必须与通过验证的不可变报告和运行 manifest 同事务发布。

## 数据与模型配置

默认 `DATA_MODE=MOCK`。允许的规范模式只有：

- `MOCK`：固定演示数据，必须持续显示 Demo 标记；
- `REAL`：只允许使用已批准的真实 Provider，不得静默混入 Mock；
- `MIXED_TEST`：仅自动化集成测试使用，不得生成普通用户报告或导出。

Phase 3 不需要 `OPENAI_API_KEY`，报告由确定性 Mock 生成器完成。Phase 6 才会引入真实模型适配器，并采用 Responses API、严格 JSON Schema 输出、`store=false`、预算控制和结构化调用审计。

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

进入 Phase 5：扩展 Evidence Registry、数值/日期/来源验证和 SEC 检索基础，在现有确定性量化与报告发布门禁之上提高证据质量。

## 免责声明

本项目只用于研究辅助与产品开发，不构成投资建议，不执行交易，也不承诺任何投资结果。Mock 数据不得被解释为真实或当前市场数据。
