import { describe, expect, it } from "vitest";

import { pollIntervalForStatus } from "@/components/research-progress";

describe("research progress polling", () => {
  it("polls active jobs every two seconds", () => {
    expect(pollIntervalForStatus("RUNNING_QUANT_ANALYSIS")).toBe(2_000);
    expect(pollIntervalForStatus(undefined)).toBe(2_000);
  });

  it.each(["COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED"] as const)("stops in %s", (status) => {
    expect(pollIntervalForStatus(status)).toBe(false);
  });
});
