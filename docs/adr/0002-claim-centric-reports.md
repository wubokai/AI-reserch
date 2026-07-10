# ADR 0002: Claim 为中心的报告模型

- 状态：Accepted
- 日期：2026-07-09

## 背景

原始报告 Schema 包含大量自由字符串，但产品原则要求每个重要结论都有类型、Evidence、数字来源和置信度。自由叙事难以确定性验证，也会鼓励模型在过渡文本中加入无来源数字。

## 决策

所有可验证陈述使用统一 Claim；报告 section 按顺序引用 Claim。数字和日期通过 NumericReference 定位到 Evidence 或 Calculation。Markdown、HTML 和 PDF 由确定性模板渲染。

LLM 输出只是候选结构。Java 验证器检查 Evidence allowlist、数字、日期、类型、新鲜度和限制，并重新计算支持度。验证失败只允许一次不增加 Evidence 的修复。

## 结果

优点：

- 可验证、可审计、可复用多种导出；
- FACT/CALCULATION/INFERENCE/OPINION 统一；
- 可从 UI Claim 直接打开 Evidence；
- 数据不足时可以安全省略 Claim。

代价：

- 报告语言自然度低于完全自由文本；
- Schema、渲染器和数字抽取验证更复杂；
- 需要区分 LLM 草稿和发布后富化结构。

该代价符合金融研究场景中“可验证优先于文采”的原则。
