import { afterEach, describe, expect, it, vi } from "vitest";

import { systemHealthResponseSchema } from "@/lib/schemas";

import { GET } from "./route";

function jsonResponse(payload: unknown) {
  return new Response(JSON.stringify(payload), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("GET /api/system-health", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("聚合 Web、API 与 Analytics 健康状态", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        jsonResponse({
          status: "UP",
          version: "0.1.0",
          timestamp: "2026-07-09T00:00:00Z",
          dataMode: "MOCK",
          components: {
            database: {
              status: "UP",
              critical: true,
              latencyMs: 2,
              message: "Database reachable",
            },
          },
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({ status: "ok", service: "analytics", version: "0.1.0" }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET();
    const payload = systemHealthResponseSchema.parse(await response.json());

    expect(payload.status).toBe("UP");
    expect(payload.services).toMatchObject({
      web: { status: "UP" },
      api: { status: "UP", dataMode: "MOCK" },
      analytics: { status: "UP" },
    });
  });

  it("保留 API DEGRADED 状态而不误判为 DOWN", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        jsonResponse({
          status: "DEGRADED",
          version: "0.2.0",
          timestamp: "2026-07-10T12:00:00Z",
          dataMode: "MOCK",
          components: {
            database: {
              status: "UP",
              critical: true,
              latencyMs: 2,
              message: "Database reachable",
            },
            durableQueue: {
              status: "UP",
              critical: true,
              latencyMs: 3,
              message: "Durable queue API available",
            },
            redis: {
              status: "DOWN",
              critical: false,
              latencyMs: 5,
              message: "Component unavailable",
            },
          },
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({ status: "ok", service: "analytics", version: "0.1.0" }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET();
    const payload = systemHealthResponseSchema.parse(await response.json());

    expect(response.status).toBe(200);
    expect(payload.status).toBe("DEGRADED");
    expect(payload.services.api).toMatchObject({
      status: "DEGRADED",
      message: "非关键依赖降级",
      dataMode: "MOCK",
    });
    expect(payload.services.analytics.status).toBe("UP");
  });

  it("上游不可达时返回可展示的降级状态", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockRejectedValueOnce(new Error("connection refused"))
      .mockResolvedValueOnce(
        jsonResponse({ status: "ok", service: "analytics", version: "0.1.0" }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET();
    const payload = systemHealthResponseSchema.parse(await response.json());

    expect(response.status).toBe(200);
    expect(payload.status).toBe("DEGRADED");
    expect(payload.services.api).toMatchObject({
      status: "DOWN",
      message: "服务当前不可达",
    });
    expect(payload.services.analytics.status).toBe("UP");
  });
});
