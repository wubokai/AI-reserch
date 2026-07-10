# ADR 0003: OpenAPI 与 JSON Schema 为契约源

- 状态：Accepted
- 日期：2026-07-09

## 决策

- Java 公共 API 以 `docs/openapi.yaml` 为规范；
- LLM Structured Outputs 以 `packages/shared-schemas/llm/*.schema.json` 为规范；
- Java/Python 分析接口使用共享 fixture 做 consumer/provider contract test；
- TypeScript、Java DTO 或测试模型不得独立改变字段含义。

## 原因

三种语言手写同一结构容易产生字段、nullable、枚举和单位漂移。机器可读契约使 CI 能验证兼容性，并允许生成客户端或类型。

## 约束

破坏性变更必须使用新 API/Schema 版本；Schema 格式校验不替代领域验证和 Evidence allowlist。
