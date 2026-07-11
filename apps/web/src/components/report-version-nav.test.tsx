import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, expect, it, vi } from "vitest";

import { ReportVersionNav } from "@/components/report-version-nav";

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>;
}

afterEach(() => vi.unstubAllGlobals());

it("列出不可变报告版本并标记当前版本", async () => {
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockResolvedValue(Response.json({
    items: [1, 2].map((version) => ({ researchId: "11111111-1111-4111-8111-111111111111", version, title: `Report v${version}`, symbol: "MU", asOfDate: "2025-12-31", validationStatus: "PASSED", dataMode: "MOCK", createdAt: `2026-07-11T12:00:0${version}Z` })),
    page: { number: 0, size: 100, totalElements: 2, totalPages: 1, first: true, last: true },
  })));
  render(<ReportVersionNav currentVersion={2} researchId="11111111-1111-4111-8111-111111111111" />, { wrapper });

  expect(await screen.findByRole("link", { name: "v1" })).toHaveAttribute("href", "/research/11111111-1111-4111-8111-111111111111/reports/1");
  expect(screen.getByRole("link", { name: "v2" })).toHaveAttribute("aria-current", "page");
});
