import { z } from "zod";

import type { SystemHealthResponse } from "@/lib/schemas";

export const dynamic = "force-dynamic";

const apiHealthStatusSchema = z.enum([
  "UP",
  "DEGRADED",
  "DOWN",
  "UNKNOWN",
]);

const apiHealthComponentSchema = z.object({
  status: apiHealthStatusSchema,
  critical: z.boolean(),
  latencyMs: z.number().int().nonnegative().nullable().optional(),
  message: z.string().nullable().optional(),
});

const apiHealthSchema = z.object({
  status: apiHealthStatusSchema,
  version: z.string().min(1),
  dataMode: z.enum(["REAL", "MOCK", "MIXED_TEST"]),
  timestamp: z.iso.datetime(),
  components: z.record(z.string(), apiHealthComponentSchema),
});

const analyticsHealthSchema = z.object({
  status: z.literal("ok"),
  version: z.string().min(1),
});

type Probe = SystemHealthResponse["services"]["api"];

async function probe(
  service: "api" | "analytics",
  url: string,
): Promise<Probe> {
  try {
    const response = await fetch(url, {
      cache: "no-store",
      headers: { "X-Request-Id": `web_health_${crypto.randomUUID()}` },
      signal: AbortSignal.timeout(2_000),
    });

    if (service === "api") {
      const payload = apiHealthSchema.parse(await response.json());
      if (!response.ok && payload.status !== "DOWN") {
        throw new Error("non-success response");
      }
      const status =
        payload.status === "UNKNOWN" ? "DOWN" : payload.status;
      return {
        status,
        service,
        version: payload.version,
        dataMode: payload.dataMode,
        ...(status === "DEGRADED"
          ? { message: "非关键依赖降级" }
          : status === "DOWN"
            ? { message: "关键依赖不可用" }
            : {}),
      };
    }

    if (!response.ok) {
      throw new Error("non-success response");
    }
    const payload = analyticsHealthSchema.parse(await response.json());
    return { status: "UP", service, version: payload.version };
  } catch {
    return {
      status: "DOWN",
      service,
      message: "服务当前不可达",
    };
  }
}

export async function GET() {
  const apiBaseUrl =
    process.env.API_INTERNAL_BASE_URL ?? "http://127.0.0.1:8080";
  const analyticsBaseUrl =
    process.env.ANALYTICS_INTERNAL_BASE_URL ?? "http://127.0.0.1:8000";

  const [api, analytics] = await Promise.all([
    probe("api", `${apiBaseUrl}/api/v1/health`),
    probe("analytics", `${analyticsBaseUrl}/analytics/v1/health`),
  ]);
  const services: SystemHealthResponse["services"] = {
    web: {
      status: "UP",
      service: "web",
      version: process.env.npm_package_version ?? "0.1.0",
      dataMode: api.dataMode ?? "MOCK",
    },
    api,
    analytics,
  };
  const status =
    api.status === "UP" && analytics.status === "UP" ? "UP" : "DEGRADED";

  return Response.json(
    {
      status,
      timestamp: new Date().toISOString(),
      services,
    } satisfies SystemHealthResponse,
    { headers: { "Cache-Control": "no-store" } },
  );
}
