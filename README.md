# AI Quant Research Assistant

面向美股与 ETF 的证据驱动研究平台。系统把研究问题拆解为取数、确定性计算、Evidence 注册、Claim 验证和报告发布步骤，目标是生成可复现、可追溯、会明确说明限制的研究辅助材料，而不是交易信号或收益承诺。

> 当前进度：Phase 0 需求与架构基线已完成；Phase 1 代码骨架与宿主机测试已完成，Gate G1 仍待 Docker build/Compose smoke 验证。研究任务持久化、量化计算、Evidence/Claim 闭环和报告生成从 Phase 2 起实现。当前页面只验证 Mock 表单与交互，不会产生真实金融结论。

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
| `apps/web` | Next.js 16、React 19、TypeScript、Tailwind、TanStack Query、Zod | Mock 研究工作台、表单校验、健康端点、组件与 E2E 测试 |
| `apps/api` | Java 21、Spring Boot 3.5、Spring Security、JPA、Flyway、Redis、Resilience4j | 分层骨架、dev-demo 安全边界、请求 ID、健康与指标端点 |
| `apps/analytics` | Python 3.12、FastAPI、Pydantic、Ruff、mypy、pytest | 版本化服务骨架、配置、JSON 日志、请求 ID、健康端点 |
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

当前验证基线：Web 6 个单元/组件测试与 3 个 Playwright E2E，API 14 个测试，Analytics 7 个测试且覆盖率 97%。本机未安装 Docker，因此 Compose image build 与完整 smoke 尚未在本机执行；CI 已定义相同 Gate。

当前尚未实现持久任务与研究业务 API、量化指标、数据 Provider、Evidence/Claim 存储、报告与导出，以及 OpenAI 调用。Java API 和 Analytics 在 Phase 1 只提供安全、配置与观测骨架及健康端点；这些能力会按 Phase 2–7 的 Gate 逐步加入。

## 数据与模型配置

默认 `DATA_MODE=MOCK`。允许的规范模式只有：

- `MOCK`：固定演示数据，必须持续显示 Demo 标记；
- `REAL`：只允许使用已批准的真实 Provider，不得静默混入 Mock；
- `MIXED_TEST`：仅自动化集成测试使用，不得生成普通用户报告或导出。

没有 `OPENAI_API_KEY` 时，后续闭环将使用确定性 Mock LLM。真实模型名称通过环境变量配置，不在代码中写死。模型适配器将采用 Responses API、严格 JSON Schema 输出、`store=false`、预算控制和结构化调用审计。

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

Phase 2 实现 Flyway 初始迁移、Research/Step 模型、PostgreSQL durable lease queue、状态机、幂等创建、重试、取消、重启恢复和所有权检查。通过 Gate G2 后，Phase 3 才接入固定 Mock 数据、核心量化、Evidence/Claim 验证、完整报告页面与三种导出。

## 免责声明

本项目只用于研究辅助与产品开发，不构成投资建议，不执行交易，也不承诺任何投资结果。Mock 数据不得被解释为真实或当前市场数据。
