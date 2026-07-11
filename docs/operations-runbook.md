# 运行、发布与故障处理手册

最后更新：2026-07-11

## 1. 干净环境启动

前置条件只有 Docker Compose。复制环境示例、替换本地 demo 密码，然后启动：

```bash
cp .env.example .env
docker compose config
docker compose up --build -d
PHASE3_CLOSED_LOOP_SMOKE=true ./scripts/smoke-test.sh
```

smoke 会验证五服务健康、Prometheus 指标、安全响应头、创建幂等、状态机、Evidence、报告、历史、
Markdown/HTML/PDF 和 Web BFF 字节一致性。默认流程不访问 SEC、FRED、Market Provider 或 OpenAI。

停止并保留数据使用 `docker compose down`；彻底删除本地卷必须由操作者显式执行
`docker compose down -v`。

## 2. 配置边界

- `development` + `DATA_MODE=MOCK` 是可交付演示配置。
- `production` 会拒绝 demo Basic auth；正式 Bearer/OIDC 未配置前启动失败关闭。
- REAL 不得混入 Mock。真实行情 Provider 未通过书面许可门禁前保持 `mock`。
- OpenAI 只允许官方 HTTPS endpoint，测试只允许 loopback；Key、模型、HMAC、价格版本和生效日
  缺一项都不会发起真实请求。
- SEC/FRED 只允许各自官方 host，测试只允许 loopback。
- `RESEARCH_MAX_EXECUTION_MINUTES` 默认 15，合法范围 1–1440；调大前必须同时评审 Provider/LLM 超时、Worker 容量与成本上限。

所有 secret 由部署 Secret Store 或环境变量注入，不写入 `.env.example`、镜像、日志或指标。

## 3. 发布步骤

1. 确认工作树干净且 OpenAPI/Schema/文档与消费者一致。
2. 执行 Web lint/typecheck/unit/build/E2E、Analytics ruff/format/mypy/pytest、API `verify`。
3. 执行 `pnpm audit`、`pip-audit --local`、secret scan 和经 SHA-256 固定的 Grype 镜像扫描；
   镜像 Critical 阻断，高风险必须在发布记录中修复或给出有期限的接受说明。
4. 构建 Compose，确认应用容器 non-root、read-only、`cap_drop: ALL`、no-new-privileges。
5. 运行完整 closed-loop smoke，两次连续 GitHub CI 必须成功。
6. 记录 commit、CI run、Schema/Prompt/Calculation/模板版本和剩余外部门禁。

## 4. 故障分诊

| 信号 | 首查 | 安全动作 |
| --- | --- | --- |
| API 5xx | Request ID、错误码、DB/queue health | 不公开堆栈；确认数据库后再重试 |
| runnable 过老 | Worker active、claim、lease/reaper | 扩 Worker 前先排除 DB 锁和失效 lease |
| outbox backlog | relay enabled、listener error code、DB locks | 保持 event ID 幂等；修复 listener 后让 relay 重放，不手工伪造 published_at |
| Provider circuit open | provider outcome/retry、官方状态 | 保持显式降级；禁止改为无界重试 |
| Redis down | health DEGRADED、cache error | 允许直取；PostgreSQL 仍是权威 |
| PDF 失败 | byte/page/font boundary | HTML/Markdown 保持可用，不改变报告状态 |
| LLM 预算/调用失败 | reservation、pricing version、failure audit | 安全回退或失败，不绕过预算 |
| `RESEARCH_EXECUTION_BUDGET_EXCEEDED` | `started_at`、当前步骤、Provider/Analytics/LLM 延迟 | 不重试同一执行；先消除慢调用或经容量评审后调整全局上限 |
| Evidence 验证失败 | validation code、Evidence/Claim lineage | 删除不安全 Claim 或 FAILED，不发布伪成功 |

## 5. 数据恢复与保留

PostgreSQL 是唯一业务真相。备份必须包含数据库版本、Flyway checksum 和应用 commit；恢复演练先在
隔离环境执行 `flyway validate`，再验证旧报告 content hash、Evidence lineage 和导出 hash。Redis 不做
业务恢复来源，可清空后重建。当前精确保留行为见 [`retention-policy.md`](retention-policy.md)。

## 6. 回滚

应用可回滚到上一镜像，但不得回写或修改已发布 Flyway migration。数据库迁移只前进；若新代码无法
读取已迁移 Schema，发布失败并恢复前一兼容镜像。报告、Evidence、Source Snapshot 和 LLM 审计均为
追加/不可变记录，修复生成新版本。
