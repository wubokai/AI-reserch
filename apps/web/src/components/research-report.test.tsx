import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ResearchReport } from "@/components/research-report";
import { DEMO_DATA_NOTICE } from "@/lib/schemas";
import {
  phase3CanonicalReport,
  phase3EvidencePage,
  phase3ReportEnvelope,
  phase3ResearchId,
} from "@/test/phase3-fixtures";

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("ResearchReport source attribution", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("在 REAL 报告页面显示 FRED 归属且不显示 Demo 标识", async () => {
    const attribution = "This product uses the FRED® API but is not endorsed or certified by the Federal Reserve Bank of St. Louis.";
    const report = {
      ...phase3ReportEnvelope,
      report: {
        ...phase3CanonicalReport,
        title: "MU source-backed research report",
        dataMode: "REAL" as const,
      },
    };
    const evidence = {
      ...phase3EvidencePage,
      dataMode: "REAL" as const,
      items: [{
        ...phase3EvidencePage.items[1],
        title: "FRED macro snapshot",
        sourceName: "FRED",
        sourceUrl: "https://fred.stlouisfed.org/fred/",
        sourceType: "GOVERNMENT_DATA",
        isDemoData: false,
        attribution,
        licensePolicyVersion: "fred_api_terms_2025-02-18",
      }],
    };
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const url = String(input);
      const payload = url.includes("/reports/") ? report : evidence;
      return new Response(JSON.stringify(payload), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }));

    render(<ResearchReport researchId={phase3ResearchId} version={1} />, { wrapper });

    expect(await screen.findByText("数据来源与归属")).toBeInTheDocument();
    expect(screen.getByText(attribution)).toBeInTheDocument();
    expect(screen.getByText("Policy fred_api_terms_2025-02-18")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "打开官方来源" }))
      .toHaveAttribute("href", "https://fred.stlouisfed.org/fred/");
    expect(screen.queryByText(DEMO_DATA_NOTICE)).not.toBeInTheDocument();
  });
});
