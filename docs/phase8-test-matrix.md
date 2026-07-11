# Phase 8 完整前端产品与 Gate G8 测试矩阵

最后更新：2026-07-11

## 1. 交付范围

| 能力 | 实现 | 结果 |
| --- | --- | --- |
| Dashboard | 任务总数、执行中、已发布、完成/部分完成与最近研究 | 通过 |
| Research Form | ticker 搜索、公司核对、语言、基准、1y/3y/5y、QUICK/STANDARD/DEEP 和分析模块 | 通过 |
| 任务控制 | 2 秒轮询、步骤耗时图、错误、warnings、取消、选择起点重试 | 通过 |
| 报告图表 | 情景隐含价格、Data Quality 环形图与步骤耗时 | 通过 |
| Claim/Evidence | Claim 类型/支持度、Evidence Drawer、Filing Chunk 搜索 | 通过 |
| Data Quality | 缺失、过期、冲突、限制和 Partial 提示均可见 | 通过 |
| 历史与版本 | 关键词/ticker/状态/日期筛选、分页、不可变版本切换 | 通过 |
| Provider 状态 | 只读脱敏能力、模式、健康、延迟、限流和说明页面 | 通过 |
| 导出状态 | Markdown/HTML/PDF 下载中、成功、失败和响应契约校验 | 通过 |
| 响应边界 | 所有网络响应经 Zod；不合规 Provider 响应被拒绝 | 通过 |
| 响应式 | 桌面完整布局和 390px 移动导航/Provider 页面 | 通过 |

## 2. 状态与操作覆盖

| 场景 | 自动化证据 | 结果 |
| --- | --- | --- |
| Loading | History、Progress、Report、Provider 均有加载反馈 | 通过 |
| Empty | 无历史、无版本、无 Provider 有明确空状态 | 通过 |
| Error | API、历史、Provider、导出失败均有安全错误反馈 | 通过 |
| Partial | Partial banner、warnings、报告入口与重试起点 | 通过 |
| Completed | 创建→轮询→报告→Evidence→三导出→历史重开 | 通过 |
| 创建/取消/重试 | Idempotency、取消成功和指定失败步骤重试 | 通过 |
| Evidence Drawer | 打开、关闭、来源、hash、schema、归属与 Demo 标识 | 通过 |
| 历史/版本 | 多条件查询参数和版本 `aria-current` | 通过 |
| 导出 | 成功文件名/Blob、HTTP 失败、content-type/dataMode 契约 | 通过 |
| 可访问性 | Landmark、label、progressbar、dialog、status/alert、键盘 focus 样式 | 通过 |

## 3. 本地验证

- ESLint：0 warning；
- TypeScript strict：通过；
- Vitest：30 个测试，0 失败；
- Next.js production build：通过，包含 `/providers` 与两个只读 BFF 路由；
- Playwright Chromium：5 个测试，0 失败；
- 闭环不访问真实 Provider、OpenAI 或互联网。

## 4. 当前限制

- 默认普通用户流程仍为 MOCK，并持续显示 `DEMO DATA — NOT REAL MARKET DATA`；
- 1y/3y/5y 与 QUICK/STANDARD/DEEP 已在需求审计检查点开放；真实 Market 仍由许可门控制，前端不伪装为可用；
- Gate G8 只验收前端产品完整性，不替代仍受 Market 许可阻塞的 Gate G7。

## 5. 远端终验

GitHub Actions [run `29144269701`](https://github.com/wubokai/AI-reserch/actions/runs/29144269701) 的 Web/Playwright、API/Testcontainers、Analytics、secret scan 与 Compose smoke 全部通过；Gate G8 完成。
