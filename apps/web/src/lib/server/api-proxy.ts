import { Buffer } from "node:buffer";
import { createHmac, randomUUID } from "node:crypto";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const VERSION_PATTERN = /^[1-9][0-9]*$/;
const RESPONSE_HEADERS = [
  "cache-control",
  "content-disposition",
  "content-length",
  "content-type",
  "etag",
  "idempotency-replayed",
  "location",
  "retry-after",
  "x-data-mode",
  "x-content-sha256",
  "x-report-version",
  "x-request-id",
] as const;

function allowed(method: string, segments: readonly string[]): boolean {
  if (segments.length === 0) {
    return method === "GET" || method === "POST";
  }
  if (!UUID_PATTERN.test(segments[0] ?? "")) {
    return false;
  }
  if (segments.length === 1) {
    return method === "GET" || method === "DELETE";
  }
  const action = segments[1];
  if (segments.length === 2) {
    return method === "GET"
      ? ["status", "evidence", "reports", "export"].includes(action ?? "")
      : method === "POST" && ["cancel", "retry"].includes(action ?? "");
  }
  return (
    method === "GET" &&
    segments.length === 3 &&
    action === "reports" &&
    VERSION_PATTERN.test(segments[2] ?? "")
  );
}

function problem(status: number, code: string, message: string): Response {
  return Response.json(
    {
      timestamp: new Date().toISOString(),
      status,
      code,
      message,
      researchId: null,
      details: {},
    },
    {
      status,
      headers: {
        "Cache-Control": "no-store",
        "Content-Type": "application/problem+json",
      },
    },
  );
}

function base64Url(value: string | Buffer): string {
  return Buffer.from(value).toString("base64url");
}

function serviceAuthorization(): string | null {
  const encodedSecret = process.env.SERVICE_JWT_HMAC_SECRET;
  if (!encodedSecret) {
    return null;
  }
  const secret = Buffer.from(encodedSecret, "base64");
  if (secret.length < 32) {
    return null;
  }
  const issuer = process.env.SERVICE_JWT_ISSUER ?? "ai-quant-web";
  const audience = process.env.SERVICE_JWT_AUDIENCE ?? "ai-quant-api";
  const subject = process.env.SERVICE_JWT_SUBJECT ?? "primary-owner";
  const email = process.env.SERVICE_JWT_EMAIL;
  if (!email || !email.includes("@")) {
    return null;
  }
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = base64Url(JSON.stringify({
    iss: issuer,
    aud: audience,
    sub: subject,
    email,
    iat: now,
    nbf: now - 5,
    exp: now + 60,
    jti: randomUUID(),
  }));
  const signingInput = `${header}.${payload}`;
  const signature = createHmac("sha256", secret)
    .update(signingInput)
    .digest("base64url");
  return `Bearer ${signingInput}.${signature}`;
}

function configuration() {
  const baseUrl = process.env.API_INTERNAL_BASE_URL ?? "http://127.0.0.1:8080";
  const bearer = serviceAuthorization();
  if (bearer) {
    return {
      baseUrl: baseUrl.replace(/\/$/, ""),
      authorization: bearer,
    };
  }
  const username = process.env.API_DEMO_USERNAME;
  const password = process.env.API_DEMO_PASSWORD;
  if (!username || !password) {
    return null;
  }
  return {
    baseUrl: baseUrl.replace(/\/$/, ""),
    authorization: `Basic ${Buffer.from(`${username}:${password}`, "utf8").toString("base64")}`,
  };
}

export async function proxyResearchRequest(
  request: Request,
  segments: readonly string[],
): Promise<Response> {
  const method = request.method.toUpperCase();
  if (!allowed(method, segments)) {
    return problem(404, "ROUTE_NOT_FOUND", "The requested research route does not exist");
  }

  const config = configuration();
  if (!config) {
    return problem(
      503,
      "BFF_NOT_CONFIGURED",
      "Research API credentials are not configured for the Web server",
    );
  }

  const incomingUrl = new URL(request.url);
  const upstreamUrl = new URL(
    `/api/v1/research${segments.length > 0 ? `/${segments.join("/")}` : ""}`,
    config.baseUrl,
  );
  upstreamUrl.search = incomingUrl.search;

  const requestId = request.headers.get("x-request-id") ?? `web_${crypto.randomUUID()}`;
  const headers = new Headers({
    Accept: request.headers.get("accept") ?? "application/json",
    Authorization: config.authorization,
    "X-Request-Id": requestId,
  });
  const contentType = request.headers.get("content-type");
  const idempotencyKey = request.headers.get("idempotency-key");
  if (contentType) {
    headers.set("Content-Type", contentType);
  }
  if (idempotencyKey) {
    headers.set("Idempotency-Key", idempotencyKey);
  }

  let body: string | undefined;
  if (method === "POST" || method === "PUT" || method === "PATCH") {
    body = await request.text();
  }

  try {
    const upstream = await fetch(upstreamUrl, {
      method,
      headers,
      ...(body === undefined ? {} : { body }),
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(30_000),
    });
    const responseHeaders = new Headers();
    for (const name of RESPONSE_HEADERS) {
      const value = upstream.headers.get(name);
      if (value !== null) {
        responseHeaders.set(name, value);
      }
    }
    responseHeaders.set("Cache-Control", upstream.headers.get("cache-control") ?? "no-store");
    responseHeaders.set("X-Request-Id", upstream.headers.get("x-request-id") ?? requestId);
    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: responseHeaders,
    });
  } catch {
    return problem(503, "API_UPSTREAM_UNAVAILABLE", "Research API is currently unavailable");
  }
}

export async function proxyReadOnlyApiRequest(
  request: Request,
  upstreamPath: "/api/v1/providers/status" | "/api/v1/securities/search",
): Promise<Response> {
  if (request.method.toUpperCase() !== "GET") {
    return problem(405, "METHOD_NOT_ALLOWED", "The requested method is not allowed");
  }
  const config = configuration();
  if (!config) {
    return problem(503, "BFF_NOT_CONFIGURED", "Research API credentials are not configured for the Web server");
  }
  const incomingUrl = new URL(request.url);
  const upstreamUrl = new URL(upstreamPath, config.baseUrl);
  upstreamUrl.search = incomingUrl.search;
  const requestId = request.headers.get("x-request-id") ?? `web_${crypto.randomUUID()}`;
  try {
    const upstream = await fetch(upstreamUrl, {
      headers: {
        Accept: "application/json",
        Authorization: config.authorization,
        "X-Request-Id": requestId,
      },
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(15_000),
    });
    const responseHeaders = new Headers({
      "Cache-Control": upstream.headers.get("cache-control") ?? "no-store",
      "Content-Type": upstream.headers.get("content-type") ?? "application/json",
      "X-Request-Id": upstream.headers.get("x-request-id") ?? requestId,
    });
    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: responseHeaders,
    });
  } catch {
    return problem(503, "API_UPSTREAM_UNAVAILABLE", "Research API is currently unavailable");
  }
}
