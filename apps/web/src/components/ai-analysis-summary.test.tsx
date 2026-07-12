import { describe, expect, it } from "vitest";

import { buildReportSummary } from "@/components/ai-analysis-summary";
import { phase3CanonicalReport } from "@/test/phase3-fixtures";

describe("buildReportSummary", () => {
  it("用经过加权的情景空间给出中性判断并优先显示最高概率情景", () => {
    const summary = buildReportSummary(phase3CanonicalReport);

    expect(summary.direction).toBe("NEUTRAL");
    expect(summary.directionLabel).toBe("中性观察");
    expect(summary.trendLabel).toBe("更可能震荡");
    expect(summary.weightedChange).toBeCloseTo(-0.0175, 6);
    expect(summary.futureView).toContain("基准情景的权重最高");
    expect(summary.currentSituation).toContain("数据可靠度为 92%");
    expect(summary.currentSituation).toContain("当前市场价格为 US$100.00");
    expect(summary.currentSituation).toContain("US$105.75");
    expect(summary.opportunity).toContain("乐观情况下参考值");
    expect(summary.risk).toContain("谨慎情况下参考值");
  });

  it("在加权上涨空间明显时标记为偏积极", () => {
    const report = {
      ...phase3CanonicalReport,
      scenarioAnalysis: {
        ...phase3CanonicalReport.scenarioAnalysis,
        scenarios: phase3CanonicalReport.scenarioAnalysis.scenarios.map((scenario) => ({
          ...scenario,
          upsideDownside: "0.12",
        })) as typeof phase3CanonicalReport.scenarioAnalysis.scenarios,
      },
    };

    const summary = buildReportSummary(report);
    expect(summary.direction).toBe("BULLISH");
    expect(summary.directionLabel).toBe("偏积极");
    expect(summary.trendLabel).toBe("上涨倾向");
  });
});
