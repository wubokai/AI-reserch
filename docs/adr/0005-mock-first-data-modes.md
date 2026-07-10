# ADR 0005: Mock First 与不可混淆的数据模式

- 状态：Accepted
- 日期：2026-07-09

## 决策

Phase 1-6 的主开发和所有 CI 使用确定性 Mock Provider。Research 创建时固定 `dataMode`：

- `MOCK`：全部实质输入来自 Mock；所有 UI/导出显示 `DEMO DATA - NOT REAL MARKET DATA`；
- `REAL`：全部实质输入来自通过许可和健康检查的真实 Provider；失败时不得静默回退 Mock；
- `MIXED_TEST`：仅自动化故障测试允许，不能通过普通用户流程发布、分享或导出。

## 原因

项目必须在没有 API Key、供应商选择和许可合同的情况下形成可重复纵向闭环。自动混合真实和 Mock 会制造无法解释的数据血缘并误导用户。

## 结果

- Mock fixture 必须固定种子、时钟、as-of date 和预期计算结果；
- 每个 SourceSnapshot、Evidence、QuantResult 和 ReportVersion 继承/聚合数据模式；
- Phase 7 的每个真实 Adapter 必须独立通过许可与 Contract Test Gate。
