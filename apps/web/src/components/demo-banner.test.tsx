import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { DemoBanner } from "@/components/demo-banner";

describe("DemoBanner", () => {
  it("清楚标识演示数据不是真实市场数据", () => {
    render(<DemoBanner />);

    expect(
      screen.getByText("DEMO DATA — NOT REAL MARKET DATA"),
    ).toBeInTheDocument();
    expect(screen.getByText("dataMode: MOCK")).toBeInTheDocument();
  });
});
