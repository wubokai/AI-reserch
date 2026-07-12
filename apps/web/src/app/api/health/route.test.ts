import { afterEach, describe, expect, it, vi } from "vitest";

import { healthResponseSchema } from "@/lib/schemas";

import { GET } from "./route";

describe("GET /api/health", () => {
  afterEach(() => vi.unstubAllEnvs());

  it("返回可解析的 Mock Web 健康状态", async () => {
    vi.stubEnv("DATA_MODE", "MOCK");
    const response = GET();
    const payload = healthResponseSchema.parse(await response.json());

    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(payload).toMatchObject({
      status: "UP",
      service: "web",
      dataMode: "MOCK",
    });
  });

  it("生产环境返回真实数据模式", async () => {
    vi.stubEnv("DATA_MODE", "REAL");

    const payload = healthResponseSchema.parse(await GET().json());

    expect(payload.dataMode).toBe("REAL");
  });
});
