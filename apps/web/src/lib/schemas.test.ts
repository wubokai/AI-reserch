import { describe, expect, it } from "vitest";

import {
  reportVersionResponseSchema,
  researchRequestSchema,
  scenarioAnalysisSchema,
} from "@/lib/schemas";
import { phase3ReportEnvelope } from "@/test/phase3-fixtures";

describe("Phase 3 Web contracts", () => {
  it("accepts the canonical research_report_v1 envelope", () => {
    const parsed = reportVersionResponseSchema.parse(phase3ReportEnvelope);
    expect(parsed.report.schemaVersion).toBe("research_report_v1");
    expect(parsed.report.sections[0]?.claims[0]?.statement).toContain("累计收益");
  });

  it("requires all deterministic Bull/Base/Bear values", () => {
    const scenarios = scenarioAnalysisSchema.parse(phase3ReportEnvelope.report.scenarioAnalysis);
    expect(scenarios.scenarios.map((scenario) => scenario.name)).toEqual(["BULL", "BASE", "BEAR"]);
    expect(scenarios.weightedImpliedPrice).toBe("105.75");
  });

  it("uses the API 10-4000 query boundary and has no dataMode input", () => {
    const parsed = researchRequestSchema.parse({
      symbol: "MU",
      query: "1234567890",
      benchmark: "SPY",
      period: "5y",
      reportDepth: "STANDARD",
      includeTechnicalAnalysis: true,
      includeFundamentalAnalysis: true,
      includeMacroAnalysis: true,
    });
    expect(parsed).not.toHaveProperty("dataMode");
    expect(() => researchRequestSchema.parse({ ...parsed, query: "123456789" })).toThrow();
  });
});
