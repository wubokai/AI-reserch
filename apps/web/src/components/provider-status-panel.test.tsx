import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ProviderStatusPanel } from "@/components/provider-status-panel";

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>;
}

const payload = {
  status: "UP",
  dataMode: "MOCK",
  checkedAt: "2026-07-11T12:00:00Z",
  providers: [{
    name: "Deterministic Mock Data",
    capabilities: ["MARKET_DATA", "FUNDAMENTALS", "FILINGS", "MACRO"],
    mode: "MOCK",
    status: "UP",
    configured: true,
    lastCheckedAt: "2026-07-11T12:00:00Z",
    lastSuccessAt: "2026-07-11T12:00:00Z",
    latencyMs: 0,
    rateLimit: { limited: false, remaining: null, resetsAt: null },
    message: "Fixed versioned fixtures",
  }],
};

describe("ProviderStatusPanel", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("展示脱敏 Provider 能力与健康状态", async () => {
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockResolvedValue(Response.json(payload)));
    render(<ProviderStatusPanel />, { wrapper });

    expect(await screen.findByText("Deterministic Mock Data")).toBeInTheDocument();
    expect(screen.getByText("MARKET_DATA")).toBeInTheDocument();
    expect(screen.getByText("Fixed versioned fixtures")).toBeInTheDocument();
  });

  it("拒绝不符合 Zod 契约的响应", async () => {
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockResolvedValue(Response.json({ ...payload, status: "BROKEN" })));
    render(<ProviderStatusPanel />, { wrapper });

    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
