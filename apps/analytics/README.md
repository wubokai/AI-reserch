# Analytics Service

Python 3.12 / FastAPI 内部确定性计算服务。Phase 4 提供：

```text
GET /analytics/v1/health
POST /analytics/v1/returns
POST /analytics/v1/risk
POST /analytics/v1/technicals
POST /analytics/v1/fundamentals
POST /analytics/v1/valuation
POST /analytics/v1/scenarios
POST /analytics/v1/full-analysis
```

当前边界是刻意收窄的：

- 不访问数据库或 Redis；
- 不调用行情、基本面或其他 Provider；
- 不调用 LLM；
- 不访问网络或自行补齐输入；
- 浏览器不得直接调用，由 Java API 组装已登记输入。

计算契约固定为 `calculationVersion=quant_v1`。HTTP 边界只接受十进制字符串；
结果拒绝 NaN/Infinity，按指标精度使用 HALF_EVEN 舍入，并保持指标、warning 和
snapshot ID 的确定性顺序。Phase 4 的 `full-analysis` 包含 9 项收益、13 项风险、
16 项技术指标、确定性 Trend Classification、16 项基本面、9 项估值和 10 项
Bull/Base/Bear 情景结果；数据缺失时保留结构化 `NOT_AVAILABLE/NOT_APPLICABLE`，不填造数值。

测试基线包含提交到仓库的 100 日收益/基准黄金集、独立预期值、完整多期基本面 fixture、
手算回撤样例、最小样本边界、币种/期间错配、负 EPS/权益/EBITDA、异常税率、ETF 能力矩阵和
Java consumer contract。具体证据见 `docs/phase4-test-matrix.md`。

`httpx` 仅用于测试客户端，因此位于 `dev` extra。当前指标只需 Python 标准库；
`pandas`、`numpy`、`scipy`、`statsmodels` 与 `scikit-learn` 不因占位而引入。

## 本地命令

```bash
python3.12 -m venv .venv
.venv/bin/python -m pip install -e '.[dev]'
.venv/bin/ruff check .
.venv/bin/ruff format --check .
.venv/bin/mypy
.venv/bin/pytest
.venv/bin/uvicorn ai_quant_analytics.main:app --host 0.0.0.0 --port 8000
```

## 配置

| 环境变量 | 默认值 | 允许值 |
| --- | --- | --- |
| `ANALYTICS_SERVICE_NAME` | `analytics` | 1–64 个字母、数字、`.`、`_`、`-` |
| `ANALYTICS_ENVIRONMENT` | `local` | `local`, `test`, `development`, `staging`, `production` |
| `ANALYTICS_LOG_LEVEL` | `INFO` | `DEBUG`, `INFO`, `WARNING`, `ERROR`, `CRITICAL` |

日志为单行 JSON。服务接受合法 `X-Request-Id`，否则生成新的 ID，并始终在响应头返回。
