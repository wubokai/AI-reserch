# Analytics Service

Python 3.12 / FastAPI 内部服务。Phase 1 只提供进程健康检查：

```text
GET /analytics/v1/health
```

当前边界是刻意收窄的：

- 不访问数据库或 Redis；
- 不调用行情、基本面或其他 Provider；
- 不调用 LLM；
- 不执行 Phase 4 才引入的量化计算；
- 浏览器不得直接调用，后续由 Java API 组装已登记输入。

`httpx` 仅用于测试客户端，因此位于 `dev` extra。`pandas`、`numpy`、`scipy`、
`statsmodels` 与 `scikit-learn` 留到 Phase 4，在有实际计算代码时再加入。

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
