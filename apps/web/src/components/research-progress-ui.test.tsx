import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ResearchProgress } from "@/components/research-progress";
import { phase3ResearchId } from "@/test/phase3-fixtures";

const now = "2026-07-11T12:00:00Z";

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>;
}

function status(state: "RUNNING_QUANT_ANALYSIS" | "PARTIALLY_COMPLETED") {
  return {
    researchId: phase3ResearchId,
    status: state,
    progress: state === "PARTIALLY_COMPLETED" ? 100 : 65,
    currentStep: state === "PARTIALLY_COMPLETED" ? null : "RUN_QUANT_ANALYSIS",
    completedSteps: state === "PARTIALLY_COMPLETED" ? 10 : 6,
    totalSteps: 11,
    cancellationRequested: false,
    dataMode: "MOCK",
    error: null,
    steps: [{ step: "RUN_QUANT_ANALYSIS", status: state === "PARTIALLY_COMPLETED" ? "FAILED" : "RUNNING", attemptCount: 1, durationMs: 125, retryable: true, startedAt: now, completedAt: state === "PARTIALLY_COMPLETED" ? now : null, error: state === "PARTIALLY_COMPLETED" ? { code: "OPTIONAL_MODULE_FAILED", message: "Optional module failed", retryable: true, failedStep: "RUN_QUANT_ANALYSIS" } : null }],
    updatedAt: now,
  };
}

function detail(state: "RUNNING_QUANT_ANALYSIS" | "PARTIALLY_COMPLETED") {
  return {
    researchId: phase3ResearchId,
    title: "MU research",
    query: "分析 MU 的增长动力、周期风险和财务质量",
    symbol: "MU",
    companyName: "Micron Technology, Inc.",
    benchmark: "SPY",
    status: state,
    progress: state === "PARTIALLY_COMPLETED" ? 100 : 65,
    reportDepth: "STANDARD",
    dataMode: "MOCK",
    latestReportVersion: state === "PARTIALLY_COMPLETED" ? 1 : null,
    createdAt: now,
    updatedAt: now,
    completedAt: state === "PARTIALLY_COMPLETED" ? now : null,
    request: { symbol: "MU", query: "分析 MU 的增长动力、周期风险和财务质量", benchmark: "SPY", period: "5y", reportDepth: "STANDARD", includeTechnicalAnalysis: true, includeFundamentalAnalysis: true, includeMacroAnalysis: true, locale: "zh-CN", companyName: "Micron Technology, Inc." },
    currentStep: state === "PARTIALLY_COMPLETED" ? null : "RUN_QUANT_ANALYSIS",
    startedAt: now,
    cancellationRequested: false,
    lastError: null,
    warnings: [{ code: "PARTIAL_DATA", message: "部分数据不可用" }],
    links: { self: "self", status: "status" },
    latestReport: null,
  };
}

describe("ResearchProgress phase 8 states", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("展示 Partial、warnings 并允许选择失败步骤重试", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.endsWith("/retry") && init?.method === "POST") return Response.json({ researchId: phase3ResearchId, status: "QUEUED", dataMode: "MOCK", createdAt: now, links: { self: "self", status: "status" } }, { status: 202 });
      return Response.json(url.endsWith("/status") ? status("PARTIALLY_COMPLETED") : detail("PARTIALLY_COMPLETED"));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<ResearchProgress researchId={phase3ResearchId} />, { wrapper });

    expect(await screen.findByText(/报告已安全发布/)).toBeInTheDocument();
    expect(screen.getByText(/PARTIAL_DATA/)).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("重试起点"), "RUN_QUANT_ANALYSIS");
    await userEvent.click(screen.getByRole("button", { name: "重试任务" }));

    expect(await screen.findByText("重试任务已进入队列。")).toBeInTheDocument();
    const retryCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/retry"));
    expect(JSON.parse(String(retryCall?.[1]?.body))).toMatchObject({ fromStep: "RUN_QUANT_ANALYSIS" });
  });

  it("活动任务取消后给出成功反馈", async () => {
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.endsWith("/cancel") && init?.method === "POST") return Response.json({ ...status("RUNNING_QUANT_ANALYSIS"), status: "CANCELLED", currentStep: null }, { status: 202 });
      return Response.json(url.endsWith("/status") ? status("RUNNING_QUANT_ANALYSIS") : detail("RUNNING_QUANT_ANALYSIS"));
    }));
    render(<ResearchProgress researchId={phase3ResearchId} />, { wrapper });

    await userEvent.click(await screen.findByRole("button", { name: "取消任务" }));
    await waitFor(() => expect(screen.getByText("取消请求已接受。")).toBeInTheDocument());
  });
});
