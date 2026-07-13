import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ResearchInsightsPanel } from "@/components/research-insights";
import { phase3Insights, phase3ResearchId } from "@/test/phase3-fixtures";

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("ResearchInsightsPanel", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("展示技术图、市场隐含预期、敏感性矩阵和同行比较", async () => {
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockResolvedValue(new Response(
      JSON.stringify(phase3Insights),
      { status: 200, headers: { "Content-Type": "application/json" } },
    )));

    render(<ResearchInsightsPanel researchId={phase3ResearchId} version={1} />, { wrapper });

    expect(await screen.findByRole("heading", { name: "价格与技术趋势" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "估值解释与市场预期" })).toBeInTheDocument();
    expect(screen.getByText("股价敏感性矩阵")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "同行公司比较" })).toBeInTheDocument();
    expect(screen.getByText("市场隐含收入增长")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "MU" })).toHaveAttribute(
      "href",
      `/research/${phase3ResearchId}/reports/1`,
    );
    fireEvent.click(screen.getByRole("button", { name: "3个月" }));
    expect(screen.getByRole("button", { name: "3个月" })).toHaveClass("bg-white");
  });
});
