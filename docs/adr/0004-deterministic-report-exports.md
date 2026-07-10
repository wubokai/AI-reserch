# ADR 0004: 确定性模板生成三种导出

- 状态：Accepted
- 日期：2026-07-09

## 决策

同一已验证 report snapshot 先由 Java 模板生成规范 Markdown 和受限 HTML，再由服务端 PDF 渲染器将该 HTML 与本地字体/内联 SVG 转为 PDF。三种格式不得分别调用 LLM。

MVP 优先采用 Java 内嵌 HTML-to-PDF 方案，避免 Java API 反向依赖 Web 服务。若中文分页或图表兼容性评测不通过，可在 Phase 9 通过新 ADR 切换到隔离的 headless Chromium renderer。

## 约束

- 模板不加载远程资源；
- 每份导出记录 reportVersion、templateVersion、生成时间和哈希；
- PDF 失败不影响网页、Markdown 或 HTML 报告；
- Mock 标识、数据截至时间、Evidence 和免责声明为不可移除区域；
- 中文字体必须打包且许可允许分发。
