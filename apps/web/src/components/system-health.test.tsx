import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SystemHealth } from "@/components/system-health";

describe("SystemHealth", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("用警示符号区分 DEGRADED 与 DOWN", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(
          JSON.stringify({
            status: "DEGRADED",
            timestamp: "2026-07-10T12:00:00Z",
            services: {
              web: { status: "UP", service: "web" },
              api: {
                status: "DEGRADED",
                service: "api",
                message: "非关键依赖降级",
              },
              analytics: { status: "DOWN", service: "analytics" },
            },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <SystemHealth />
      </QueryClientProvider>,
    );

    expect(
      await screen.findByLabelText("Web ↑ · API ~ · Analytics ↓"),
    ).toHaveAttribute("title", "Web ↑ · API ~ · Analytics ↓");
  });
});
