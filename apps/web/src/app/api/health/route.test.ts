import { describe, expect, it } from "vitest";

import { healthResponseSchema } from "@/lib/schemas";

import { GET } from "./route";

describe("GET /api/health", () => {
  it("返回可解析的 Mock Web 健康状态", async () => {
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
});
