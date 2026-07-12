import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ResearchForm } from "@/components/research-form";

const routerPush = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: routerPush }),
}));

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("ResearchForm", () => {
  afterEach(() => {
    routerPush.mockReset();
    vi.unstubAllGlobals();
  });

  it("拒绝少于十个字符的研究问题", async () => {
    const user = userEvent.setup();
    render(<ResearchForm />, { wrapper });

    await user.click(screen.getByRole("button", { name: "创建 DEMO 研究" }));

    expect(await screen.findByText("研究问题至少需要 10 个字符")).toBeInTheDocument();
  });

  it("将 Phase 3 固定量化契约保持为必选", () => {
    render(<ResearchForm />, { wrapper });

    expect(screen.getByRole("checkbox", { name: "量化与技术" }))
      .toBeChecked();
    expect(screen.getByRole("checkbox", { name: "量化与技术" }))
      .toBeDisabled();
    expect(screen.getByText(/上市历史不足时自动按实际可用区间计算/))
      .toBeInTheDocument();
  });

  it("提交不含 dataMode 的请求并跳转到持久任务", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({
        researchId: "11111111-1111-4111-8111-111111111111",
        status: "QUEUED",
        dataMode: "MOCK",
        createdAt: "2026-07-10T12:00:00Z",
        links: { self: "/api/v1/research/1", status: "/api/v1/research/1/status" },
      }), { status: 202, headers: { "Content-Type": "application/json" } }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();
    render(<ResearchForm />, { wrapper });

    await user.type(screen.getByLabelText("研究问题"), "分析增长动力、周期风险、财务质量和未来主要观察因素");
    await user.click(screen.getByRole("button", { name: "创建 DEMO 研究" }));

    await waitFor(() => expect(routerPush).toHaveBeenCalledWith("/research/11111111-1111-4111-8111-111111111111"));
    const [, init] = fetchMock.mock.calls[0] ?? [];
    const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
    expect(body).not.toHaveProperty("dataMode");
    expect(init?.headers).toEqual(expect.objectContaining({ "Idempotency-Key": expect.any(String) }));
  });
});
