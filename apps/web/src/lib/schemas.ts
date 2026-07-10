import { z } from "zod";

export const dataModeSchema = z.enum(["REAL", "MOCK", "MIXED_TEST"]);

export const healthResponseSchema = z.object({
  status: z.literal("UP"),
  service: z.literal("web"),
  dataMode: dataModeSchema,
  timestamp: z.iso.datetime(),
});

export type HealthResponse = z.infer<typeof healthResponseSchema>;

export const serviceProbeSchema = z.object({
  status: z.enum(["UP", "DEGRADED", "DOWN"]),
  service: z.enum(["web", "api", "analytics"]),
  version: z.string().min(1).optional(),
  dataMode: dataModeSchema.optional(),
  message: z.string().min(1).optional(),
});

export const systemHealthResponseSchema = z.object({
  status: z.enum(["UP", "DEGRADED"]),
  timestamp: z.iso.datetime(),
  services: z.object({
    web: serviceProbeSchema,
    api: serviceProbeSchema,
    analytics: serviceProbeSchema,
  }),
});

export type SystemHealthResponse = z.infer<
  typeof systemHealthResponseSchema
>;

export const researchRequestSchema = z.object({
  symbol: z
    .string()
    .trim()
    .toUpperCase()
    .regex(/^[A-Z][A-Z0-9.-]{0,9}$/, "请输入有效的美股代码"),
  query: z
    .string()
    .trim()
    .min(12, "研究问题至少需要 12 个字符")
    .max(1200, "研究问题不能超过 1200 个字符"),
  benchmark: z.enum(["SPY", "QQQ"]),
  period: z.literal("5y"),
  reportDepth: z.literal("STANDARD"),
  includeTechnicalAnalysis: z.boolean(),
  includeFundamentalAnalysis: z.boolean(),
  includeMacroAnalysis: z.boolean(),
  dataMode: z.literal("MOCK"),
});

export type ResearchRequest = z.infer<typeof researchRequestSchema>;
