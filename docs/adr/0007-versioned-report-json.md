# ADR 0007: Versioned report JSON 是报告真源

- 状态：Accepted
- 日期：2026-07-09

## 决策

每个已发布 `ReportVersion` 保存通过验证的、不可变 `report_json`，并绑定 Claim、Evidence、SourceSnapshot、Calculation、Prompt、Schema 和模板版本。Markdown、HTML、PDF 均从同一 report JSON 确定性生成。

重试、修复或输入变化创建新版本，不覆盖旧版本；重新打开历史报告不从最新 Provider 数据动态重算。

## 原因

多种导出分别生成会产生数字、引用和免责声明漂移；动态重算旧报告会破坏审计与复现。

## 结果

- 三种导出内容必须共享 Claim 顺序和 Evidence；
- 导出失败只影响对应 ExportArtifact；
- 报告版本保留输入 manifest/hash；
- 模板升级不会静默改变已存导出，重新导出时记录新 templateVersion。
