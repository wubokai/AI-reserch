# LLM Schemas

These JSON Schemas are the canonical Structured Outputs contracts.

- `research-plan.schema.json`: constrained research plan.
- `research-report.schema.json`: claim-centric final report.

`research_report_v1` exposes Bull/Base/Bear scenario inputs and deterministic
outputs as decimal strings plus `weightedImpliedPrice`; every narrative scenario
conclusion remains a normal Claim with Evidence/Calculation references.

Rules:

1. Schema changes require a new `schemaVersion` and compatibility test.
2. Java validates model responses and applies domain/evidence validation after JSON Schema validation.
3. IDs in model output are untrusted strings until checked against the per-research allowlist.
4. Generated language-specific types must not be edited directly.
