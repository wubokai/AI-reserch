import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { afterEach, expect, it, vi } from "vitest";

import { ResearchHistory } from "@/components/research-history";

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>;
}

afterEach(() => vi.unstubAllGlobals());

it("支持关键词、证券、状态和日期范围筛选并显示 Empty 状态", async () => {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ items: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true } }));
  vi.stubGlobal("fetch", fetchMock);
  render(<ResearchHistory />, { wrapper });

  expect(await screen.findByText("没有符合条件的研究任务。")).toBeInTheDocument();
  await userEvent.type(screen.getByPlaceholderText("搜索证券或研究问题"), "supply");
  await userEvent.type(screen.getByLabelText("按证券筛选"), "mu");
  await userEvent.selectOptions(screen.getByLabelText("按状态筛选"), "COMPLETED");
  await userEvent.type(screen.getByLabelText("开始日期"), "2026-07-01");
  await userEvent.type(screen.getByLabelText("结束日期"), "2026-07-11");

  await waitFor(() => {
    const url = String(fetchMock.mock.calls.at(-1)?.[0]);
    expect(url).toContain("q=supply");
    expect(url).toContain("symbol=MU");
    expect(url).toContain("status=COMPLETED");
    expect(url).toContain("from=");
    expect(url).toContain("to=");
  });
});
