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
    const detail = {
      researchId: phase3ResearchId,
      title: "MU source-backed research report",
      query: "分析 MU 的增长动力、周期风险和财务质量",
      symbol: "MU",
      companyName: "Micron Technology, Inc.",
      benchmark: "SPY",
      status: "PARTIALLY_COMPLETED",
      progress: 100,
      reportDepth: "STANDARD",
      dataMode: "REAL",
      latestReportVersion: 1,
      createdAt: "2026-07-10T12:00:00Z",
      updatedAt: "2026-07-10T12:00:10Z",
      completedAt: "2026-07-10T12:00:10Z",
      request: { symbol: "MU", companyName: "Micron Technology, Inc.", query: "分析 MU 的增长动力、周期风险和财务质量", locale: "zh-CN", benchmark: "SPY", period: "5y", reportDepth: "STANDARD", includeTechnicalAnalysis: true, includeFundamentalAnalysis: true, includeMacroAnalysis: true },
      currentStep: null,
      startedAt: "2026-07-10T12:00:00Z",
      cancellationRequested: false,
      lastError: null,
      warnings: [{ code: "MARKET_HISTORY_SHORTER_THAN_REQUESTED", message: "上市历史不足，报告已按实际可用日期计算。", evidenceIds: [] }],
      links: { self: "self", status: "status", evidence: "evidence", reports: "reports" },
      latestReport: null,
    };
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const url = String(input);
      const payload = url.includes("/reports/")
        ? report
        : url.includes("/evidence")
          ? evidence
          : detail;
      return new Response(JSON.stringify(payload), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }));

    render(<ResearchReport researchId={phase3ResearchId} version={1} />, { wrapper });

    expect(await screen.findByText("这些数据来自哪里")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "AI 分析总结" })).toBeInTheDocument();
    expect(screen.getByText(attribution)).toBeInTheDocument();
    expect(screen.getByText("fred_api_terms_2025-02-18")).toBeInTheDocument();
    expect(screen.getByText("报告依据（1 条）")).toBeInTheDocument();
    expect(await screen.findByText("本报告的数据范围提示")).toBeInTheDocument();
    expect(screen.getByText(/实际可用日期/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "查看官方资料" }))
      .toHaveAttribute("href", "https://fred.stlouisfed.org/fred/");
    expect(screen.queryByText(DEMO_DATA_NOTICE)).not.toBeInTheDocument();
  });
});
