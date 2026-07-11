import { afterEach, describe, expect, it, vi } from "vitest";

import { proxyReadOnlyApiRequest, proxyResearchRequest } from "@/lib/server/api-proxy";

describe("research BFF", () => {
  afterEach(() => {
    delete process.env.API_INTERNAL_BASE_URL;
    delete process.env.API_DEMO_USERNAME;
    delete process.env.API_DEMO_PASSWORD;
    delete process.env.SERVICE_JWT_HMAC_SECRET;
    delete process.env.SERVICE_JWT_ISSUER;
    delete process.env.SERVICE_JWT_AUDIENCE;
    delete process.env.SERVICE_JWT_SUBJECT;
    delete process.env.SERVICE_JWT_EMAIL;
    vi.unstubAllGlobals();
  });

  it("adds server-only Basic auth and forwards idempotency without cookies", async () => {
    process.env.API_INTERNAL_BASE_URL = "http://api.internal:8080";
    process.env.API_DEMO_USERNAME = "demo-user";
    process.env.API_DEMO_PASSWORD = "super-secret";
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      status: 202,
      headers: { "Content-Type": "application/json", "X-Request-Id": "upstream-id" },
    }));
    vi.stubGlobal("fetch", fetchMock);

    const response = await proxyResearchRequest(new Request("http://web.local/api/research", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Cookie: "browser-session=private",
        "Idempotency-Key": "stable-key",
      },
      body: JSON.stringify({ symbol: "MU" }),
    }), []);

    expect(response.status).toBe(202);
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(String(url)).toBe("http://api.internal:8080/api/v1/research");
    const headers = new Headers(init?.headers);
    expect(headers.get("authorization")).toBe(`Basic ${Buffer.from("demo-user:super-secret").toString("base64")}`);
    expect(headers.get("idempotency-key")).toBe("stable-key");
    expect(headers.get("cookie")).toBeNull();
    expect(response.headers.get("authorization")).toBeNull();
    expect(response.headers.get("x-request-id")).toBe("upstream-id");
  });

  it("uses a short-lived server-only service JWT when production credentials exist", async () => {
    process.env.API_INTERNAL_BASE_URL = "http://api.internal:8080";
    process.env.SERVICE_JWT_HMAC_SECRET = Buffer.alloc(32, 7).toString("base64");
    process.env.SERVICE_JWT_ISSUER = "ai-quant-web";
    process.env.SERVICE_JWT_AUDIENCE = "ai-quant-api";
    process.env.SERVICE_JWT_SUBJECT = "primary-owner";
    process.env.SERVICE_JWT_EMAIL = "bw2754@nyu.edu";
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    const response = await proxyResearchRequest(
      new Request("http://web.local/api/research"),
      [],
    );

    expect(response.status).toBe(200);
    const authorization = new Headers(fetchMock.mock.calls[0]?.[1]?.headers)
      .get("authorization");
    expect(authorization).toMatch(/^Bearer [^.]+\.[^.]+\.[^.]+$/);
    const payload = JSON.parse(Buffer.from(
      authorization?.split(".")[1] ?? "",
      "base64url",
    ).toString("utf8")) as Record<string, unknown>;
    expect(payload).toMatchObject({
      iss: "ai-quant-web",
      aud: "ai-quant-api",
      sub: "primary-owner",
      email: "bw2754@nyu.edu",
    });
    expect(Number(payload.exp) - Number(payload.iat)).toBe(60);
  });

  it("streams an allowlisted export with its safe response headers", async () => {
    process.env.API_DEMO_USERNAME = "demo";
    process.env.API_DEMO_PASSWORD = "password";
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockResolvedValue(new Response("# report", {
      headers: {
        "Cache-Control": "private, no-cache",
        "Content-Disposition": "attachment; filename=report.md",
        "Content-Length": "8",
        "Content-Type": "text/markdown; charset=UTF-8",
        ETag: '"content-etag"',
        "X-Content-SHA256": "a".repeat(64),
        "X-Data-Mode": "MOCK",
        "X-Report-Version": "1",
      },
    })));
    const id = "11111111-1111-4111-8111-111111111111";
    const response = await proxyResearchRequest(new Request(`http://web.local/api/research/${id}/export?format=markdown&reportVersion=1`), [id, "export"]);
    expect(await response.text()).toBe("# report");
    expect(response.headers.get("cache-control")).toBe("private, no-cache");
    expect(response.headers.get("content-disposition")).toBe("attachment; filename=report.md");
    expect(response.headers.get("content-length")).toBe("8");
    expect(response.headers.get("content-type")).toBe("text/markdown; charset=UTF-8");
    expect(response.headers.get("etag")).toBe('"content-etag"');
    expect(response.headers.get("x-content-sha256")).toBe("a".repeat(64));
    expect(response.headers.get("x-data-mode")).toBe("MOCK");
    expect(response.headers.get("x-report-version")).toBe("1");
  });

  it("fails closed when server credentials are missing", async () => {
    const response = await proxyResearchRequest(new Request("http://web.local/api/research"), []);
    expect(response.status).toBe(503);
    expect(await response.text()).not.toContain("API_DEMO_PASSWORD");
  });

  it("forwards only authenticated GET requests to read-only operational endpoints", async () => {
    process.env.API_INTERNAL_BASE_URL = "http://api.internal:8080";
    process.env.API_DEMO_USERNAME = "demo";
    process.env.API_DEMO_PASSWORD = "password";
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ status: "UP" }));
    vi.stubGlobal("fetch", fetchMock);

    const response = await proxyReadOnlyApiRequest(
      new Request("http://web.local/api/providers/status"),
      "/api/v1/providers/status",
    );

    expect(response.status).toBe(200);
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe("http://api.internal:8080/api/v1/providers/status");
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("authorization")).toMatch(/^Basic /);
  });
});
