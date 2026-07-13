import { z } from "zod";

export const DEMO_DATA_NOTICE = "DEMO DATA — NOT REAL MARKET DATA";
export const RESEARCH_DISCLAIMER =
  "仅供产品演示与研究辅助，不构成投资建议、交易建议或收益承诺。";

export const dataModeSchema = z.enum(["REAL", "MOCK", "MIXED_TEST"]);
export const researchStatusSchema = z.enum([
  "CREATED",
  "QUEUED",
  "RESOLVING_SECURITY",
  "FETCHING_MARKET_DATA",
  "FETCHING_FUNDAMENTALS",
  "FETCHING_FILINGS",
  "FETCHING_MACRO_DATA",
  "VALIDATING_DATA",
  "RUNNING_QUANT_ANALYSIS",
  "ANALYZING_FUNDAMENTALS",
  "BUILDING_EVIDENCE",
  "GENERATING_REPORT",
  "VALIDATING_REPORT",
  "COMPLETED",
  "PARTIALLY_COMPLETED",
  "FAILED",
  "CANCELLED",
]);
export const stepTypeSchema = z.enum([
  "RESOLVE_SECURITY",
  "FETCH_MARKET_DATA",
  "FETCH_FUNDAMENTALS",
  "FETCH_FILINGS",
  "FETCH_MACRO_DATA",
  "VALIDATE_DATA",
  "RUN_QUANT_ANALYSIS",
  "ANALYZE_FUNDAMENTALS",
  "BUILD_EVIDENCE",
  "GENERATE_REPORT",
  "VALIDATE_REPORT",
]);
export const stepStatusSchema = z.enum([
  "PENDING",
  "RUNNING",
  "SUCCEEDED",
  "FAILED",
  "SKIPPED",
  "CANCELLED",
]);
export const reportDepthSchema = z.enum(["QUICK", "STANDARD", "DEEP"]);
export const researchPeriodSchema = z.enum(["1y", "3y", "5y"]);
export const claimTypeSchema = z.enum([
  "FACT",
  "CALCULATION",
  "INFERENCE",
  "OPINION",
]);
export const validationStatusSchema = z.enum([
  "PENDING",
  "PASSED",
  "PASSED_WITH_WARNINGS",
  "FAILED",
]);

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

const nullableDateTimeSchema = z.iso.datetime().nullable();
const nullableStringSchema = z.string().nullable();

export const securityItemSchema = z.object({
  securityId: z.uuid(),
  symbol: z.string().min(1),
  companyName: z.string().min(1),
  exchange: z.string().min(1),
  securityType: z.enum(["COMMON_STOCK", "ETF"]),
  currency: z.string().length(3),
  sector: z.string().nullable(),
  industry: z.string().nullable(),
  cik: z.string().nullable(),
  active: z.boolean(),
  dataMode: dataModeSchema,
});

export const securitySearchResponseSchema = z.object({
  items: z.array(securityItemSchema),
  dataMode: dataModeSchema,
});

export const providerStatusSchema = z.object({
  name: z.string().min(1),
  capabilities: z.array(z.enum([
    "MARKET_DATA",
    "FUNDAMENTALS",
    "FILINGS",
    "MACRO",
    "LLM",
    "ANALYTICS",
  ])),
  mode: z.enum(["REAL", "MOCK", "DISABLED"]),
  status: z.enum(["UP", "DEGRADED", "DOWN", "UNKNOWN"]),
  configured: z.boolean(),
  lastCheckedAt: nullableDateTimeSchema.optional(),
  lastSuccessAt: nullableDateTimeSchema.optional(),
  latencyMs: z.number().int().nonnegative().nullable(),
  rateLimit: z.object({
    limited: z.boolean(),
    remaining: z.number().int().nonnegative().nullable(),
    resetsAt: nullableDateTimeSchema.optional(),
  }),
  message: z.string().nullable(),
});

export const providerStatusResponseSchema = z.object({
  status: z.enum(["UP", "DEGRADED", "DOWN", "UNKNOWN"]),
  dataMode: dataModeSchema,
  providers: z.array(providerStatusSchema),
  checkedAt: z.iso.datetime(),
});

export type ProviderStatusResponse = z.infer<typeof providerStatusResponseSchema>;

const researchRequestObjectSchema = z.object({
  symbol: z
    .string()
    .trim()
    .toUpperCase()
    .regex(/^[A-Z][A-Z0-9.-]{0,9}$/, "请输入有效的美股代码")
    .optional(),
  query: z
    .string()
    .trim()
    .min(10, "研究问题至少需要 10 个字符")
    .max(4000, "研究问题不能超过 4000 个字符"),
  companyName: z.string().trim().min(1).max(200).optional(),
  locale: z.enum(["zh-CN", "en-US"]).optional(),
  benchmark: z.enum(["SPY", "QQQ"]),
  period: researchPeriodSchema,
  reportDepth: reportDepthSchema,
  includeTechnicalAnalysis: z.boolean(),
  includeFundamentalAnalysis: z.boolean(),
  includeMacroAnalysis: z.boolean(),
});

const hasResearchTarget = (request: {
  symbol?: string | null | undefined;
  companyName?: string | null | undefined;
}) => Boolean(request.symbol || request.companyName);

export const researchRequestSchema = researchRequestObjectSchema.refine(hasResearchTarget, {
  message: "证券代码或公司名称至少填写一项",
  path: ["symbol"],
});

export type ResearchRequest = z.infer<typeof researchRequestSchema>;

export const problemDetailsSchema = z.object({
  timestamp: z.string().optional(),
  status: z.number().int(),
  code: z.string().min(1),
  message: z.string().min(1),
  researchId: z.uuid().nullable().optional(),
  details: z.unknown().optional(),
});

export const researchLinksSchema = z.object({
  self: z.string(),
  status: z.string(),
  evidence: z.string().optional(),
  reports: z.string().optional(),
  export: z.string().optional(),
});

export const researchAcceptedSchema = z.object({
  researchId: z.uuid(),
  status: researchStatusSchema,
  dataMode: dataModeSchema,
  createdAt: z.iso.datetime(),
  links: researchLinksSchema,
});

export const errorSummarySchema = z.object({
  code: z.string().min(1),
  message: z.string().min(1),
  retryable: z.boolean(),
  failedStep: stepTypeSchema.nullable().optional(),
});

export const warningSchema = z.object({
  code: z.string().min(1),
  message: z.string().min(1),
  evidenceIds: z.array(z.string()).optional(),
});

export const pageMetadataSchema = z.object({
  number: z.number().int().nonnegative(),
  size: z.number().int().positive(),
  totalElements: z.number().int().nonnegative(),
  totalPages: z.number().int().nonnegative(),
  first: z.boolean(),
  last: z.boolean(),
});

export const researchItemSchema = z.object({
  researchId: z.uuid(),
  title: nullableStringSchema.optional(),
  query: z.string(),
  symbol: nullableStringSchema,
  companyName: nullableStringSchema,
  benchmark: nullableStringSchema.optional(),
  status: researchStatusSchema,
  progress: z.number().int().min(0).max(100),
  reportDepth: reportDepthSchema,
  dataMode: dataModeSchema,
  latestReportVersion: z.number().int().positive().nullable().optional(),
  createdAt: z.iso.datetime(),
  updatedAt: z.iso.datetime(),
  completedAt: nullableDateTimeSchema.optional(),
});

export const researchPageSchema = z.object({
  items: z.array(researchItemSchema),
  page: pageMetadataSchema,
});

export const researchStepSchema = z.object({
  step: stepTypeSchema,
  status: stepStatusSchema,
  attemptCount: z.number().int().nonnegative(),
  startedAt: nullableDateTimeSchema.optional(),
  completedAt: nullableDateTimeSchema.optional(),
  durationMs: z.number().int().nonnegative().nullable().optional(),
  retryable: z.boolean(),
  error: errorSummarySchema.nullable().optional(),
});

export const researchStatusResponseSchema = z.object({
  researchId: z.uuid(),
  status: researchStatusSchema,
  progress: z.number().int().min(0).max(100),
  currentStep: stepTypeSchema.nullable().optional(),
  completedSteps: z.number().int().nonnegative(),
  totalSteps: z.number().int().positive(),
  cancellationRequested: z.boolean(),
  dataMode: dataModeSchema,
  error: errorSummarySchema.nullable().optional(),
  steps: z.array(researchStepSchema),
  updatedAt: z.iso.datetime(),
});

export const researchDetailSchema = researchItemSchema.extend({
  request: researchRequestObjectSchema.extend({
    locale: z.enum(["zh-CN", "en-US"]).optional(),
    companyName: z.string().nullable().optional(),
    startDate: z.iso.date().nullable().optional(),
    endDate: z.iso.date().nullable().optional(),
  }).refine(hasResearchTarget, {
    message: "证券代码或公司名称至少填写一项",
    path: ["symbol"],
  }),
  currentStep: stepTypeSchema.nullable().optional(),
  startedAt: nullableDateTimeSchema.optional(),
  cancellationRequested: z.boolean(),
  lastError: errorSummarySchema.nullable().optional(),
  warnings: z.array(warningSchema),
  links: researchLinksSchema,
  latestReport: z.unknown().nullable().optional(),
});

const publicIdSchema = z.string().regex(/^[a-z]+_[A-Za-z0-9_-]{1,64}$/);
const decimalValueSchema = z.union([
  z.number().finite(),
  z.string().regex(/^-?[0-9]+(?:\.[0-9]+)?$/),
]);

export const numericReferenceSchema = z.object({
  token: z.string().min(1),
  normalizedValue: z.string().regex(/^-?[0-9]+(?:\.[0-9]+)?$/),
  unit: z.string().min(1),
  sourceKind: z.enum(["EVIDENCE", "CALCULATION"]),
  sourceId: z.string().min(1),
  jsonPointer: z.string().min(1),
  tolerance: z.string().regex(/^[0-9]+(?:\.[0-9]+)?$/),
});

export const dateReferenceSchema = z.object({
  token: z.iso.date(),
  normalizedDate: z.iso.date(),
  sourceKind: z.enum(["EVIDENCE", "CALCULATION"]),
  sourceId: z.string().min(1),
  jsonPointer: z.string().min(1),
});

export const claimSchema = z.object({
  id: z.string().regex(/^cl_[A-Za-z0-9_-]{1,64}$/),
  statement: z.string().min(1),
  claimType: claimTypeSchema,
  materiality: z.enum(["MATERIAL", "SUPPORTING"]),
  evidenceIds: z.array(z.string().regex(/^ev_[A-Za-z0-9_-]{1,64}$/)),
  calculationIds: z.array(z.string()).default([]),
  numericReferences: z.array(numericReferenceSchema).default([]),
  dateReferences: z.array(dateReferenceSchema).default([]),
  confidence: z.number().min(0).max(1),
  limitations: z.array(z.string()).default([]),
});

export const reportSectionSchema = z.object({
  id: z.string().regex(/^[a-z][a-z0-9_-]{1,63}$/),
  heading: z.string().min(1),
  claims: z.array(claimSchema),
  transitionText: z.string(),
});

export const reportScenarioSchema = z.object({
  name: z.enum(["BULL", "BASE", "BEAR"]),
  probability: decimalValueSchema,
  revenueGrowth: decimalValueSchema,
  targetEbitdaMargin: decimalValueSchema,
  evToEbitdaMultiple: decimalValueSchema,
  valuationMethod: z.enum(["EV_EBITDA", "EV_REVENUE"]).optional(),
  valuationMultiple: decimalValueSchema.optional(),
  impliedEquityValue: decimalValueSchema,
  impliedPrice: decimalValueSchema,
  upsideDownside: decimalValueSchema,
});

export const scenarioAnalysisSchema = z.object({
  calculationId: publicIdSchema,
  currency: z.string().length(3).optional(),
  currentPrice: decimalValueSchema.optional(),
  scenarios: z.array(reportScenarioSchema).length(3),
  weightedImpliedPrice: decimalValueSchema,
  summaryClaims: z.array(claimSchema),
});

export const dataQualitySchema = z.object({
  score: z.number().min(0).max(1),
  missingData: z.array(z.string()),
  staleEvidenceIds: z.array(z.string()),
  sourceConflicts: z.array(z.string()),
  limitations: z.array(z.string()),
});

export const canonicalResearchReportSchema = z.object({
  schemaVersion: z.literal("research_report_v1"),
  title: z.string().min(1),
  symbol: z.string().min(1),
  securityType: z.enum(["COMMON_STOCK", "ETF"]),
  locale: z.enum(["zh-CN", "en-US"]),
  asOfDate: z.iso.date(),
  dataMode: dataModeSchema,
  sections: z.array(reportSectionSchema),
  bullCase: z.array(claimSchema),
  bearCase: z.array(claimSchema),
  catalysts: z.array(claimSchema),
  risks: z.array(
    z.object({
      category: z.enum([
        "BUSINESS",
        "FINANCIAL",
        "VALUATION",
        "MARKET",
        "REGULATORY",
        "EXECUTION",
        "DATA_QUALITY",
      ]),
      claim: claimSchema,
    }),
  ),
  scenarioAnalysis: scenarioAnalysisSchema,
  dataQuality: dataQualitySchema,
  conclusion: z.array(claimSchema),
  disclaimer: z.string().min(1),
});

const reportVersionMetadataSchema = z.object({
  researchId: z.uuid(),
  version: z.number().int().positive(),
  validationStatus: validationStatusSchema,
  contentHash: z.string().regex(/^[a-f0-9]{64}$/),
  createdAt: z.iso.datetime(),
  generatedAt: z.iso.datetime().optional(),
});

const reportVersionEnvelopeSchema = reportVersionMetadataSchema.extend({
  report: canonicalResearchReportSchema,
});

const flatReportVersionSchema = reportVersionMetadataSchema
  .extend(canonicalResearchReportSchema.shape)
  .transform((value) => {
    const {
      researchId,
      version,
      validationStatus,
      contentHash,
      createdAt,
      generatedAt,
      ...report
    } = value;
    return {
      researchId,
      version,
      validationStatus,
      contentHash,
      createdAt,
      ...(generatedAt ? { generatedAt } : {}),
      report,
    };
  });

export const reportVersionResponseSchema = z.union([
  reportVersionEnvelopeSchema,
  flatReportVersionSchema,
]);

export const reportVersionSummarySchema = z.object({
  researchId: z.uuid(),
  version: z.number().int().positive(),
  title: z.string(),
  symbol: z.string(),
  asOfDate: z.iso.date(),
  validationStatus: validationStatusSchema,
  dataMode: dataModeSchema,
  contentHash: z.string().regex(/^[a-f0-9]{64}$/).optional(),
  createdAt: z.iso.datetime(),
});

export const reportVersionPageSchema = z.object({
  items: z.array(reportVersionSummarySchema),
  page: pageMetadataSchema,
});

export const evidenceSchema = z.object({
  evidenceId: z.string().regex(/^ev_[A-Za-z0-9_-]{1,64}$/),
  evidenceType: z.enum([
    "MARKET_PRICE",
    "FINANCIAL_METRIC",
    "SEC_FILING",
    "MACRO_OBSERVATION",
    "QUANT_RESULT",
    "COMPANY_PROFILE",
    "NEWS_ARTICLE",
    "OTHER",
  ]),
  title: z.string(),
  summary: z.string(),
  value: z.unknown().optional(),
  unit: z.string().nullable().optional(),
  sourceName: z.string(),
  sourceUrl: z.url().nullable().optional(),
  sourceType: z.string(),
  publishedAt: nullableDateTimeSchema.optional(),
  retrievedAt: z.iso.datetime(),
  effectiveDate: z.iso.date().nullable().optional(),
  isPrimarySource: z.boolean(),
  freshnessStatus: z.enum(["FRESH", "STALE", "VERY_STALE", "UNKNOWN"]),
  qualityScore: z.number().min(0).max(1),
  rawDataHash: z.string().regex(/^[a-f0-9]{64}$/),
  isDemoData: z.boolean(),
  relatedClaimIds: z.array(z.string()),
  sourceSnapshotId: z.uuid().nullable(),
  sourceSchemaVersion: z.string().nullable(),
  normalizedDataHash: z.string().regex(/^[a-f0-9]{64}$/).nullable(),
  attribution: z.string().nullable(),
  licensePolicyVersion: z.string().nullable(),
});

export const evidencePageSchema = z.object({
  items: z.array(evidenceSchema),
  page: pageMetadataSchema,
  dataMode: dataModeSchema,
});

export const evidenceSearchResultSchema = z.object({
  evidenceId: z.string().regex(/^ev_[A-Za-z0-9_-]{1,64}$/),
  filingId: z.uuid(),
  chunkId: z.uuid(),
  externalDocumentId: z.string(),
  formType: z.string(),
  filingDate: z.iso.date(),
  sectionName: z.string(),
  chunkIndex: z.number().int().nonnegative(),
  excerpt: z.string(),
  citationLocator: z.string(),
  rank: z.number().nonnegative(),
  isDemoData: z.boolean(),
});

export const evidenceSearchResponseSchema = z.object({
  query: z.string(),
  items: z.array(evidenceSearchResultSchema),
  dataMode: dataModeSchema,
});

const nullableDecimalSchema = decimalValueSchema.nullable();

export const researchInsightsSchema = z.object({
  researchId: z.uuid(),
  reportVersion: z.number().int().positive(),
  dataMode: dataModeSchema,
  priceChart: z.object({
    symbol: z.string().min(1),
    currency: z.string().length(3),
    provider: z.string().nullable(),
    asOfDate: z.iso.date().nullable(),
    retrievedAt: z.iso.datetime().nullable(),
    methodology: z.string().min(1),
    points: z.array(z.object({
      date: z.iso.date(),
      open: decimalValueSchema,
      high: decimalValueSchema,
      low: decimalValueSchema,
      close: decimalValueSchema,
      adjustedClose: decimalValueSchema,
      volume: z.number().int().nonnegative(),
      ma20: nullableDecimalSchema,
      ma50: nullableDecimalSchema,
    })),
    rangeStats: z.array(z.object({
      range: z.enum(["3M", "1Y", "3Y", "MAX"]),
      periodStart: z.iso.date(),
      periodEnd: z.iso.date(),
      firstPrice: decimalValueSchema,
      lastPrice: decimalValueSchema,
      periodReturn: decimalValueSchema,
      high: decimalValueSchema,
      low: decimalValueSchema,
      averageVolume: decimalValueSchema,
    })).length(4),
    technicalSummary: z.object({
      currentPrice: nullableDecimalSchema,
      priceVsMa20: nullableDecimalSchema,
      priceVsMa50: nullableDecimalSchema,
      signal: z.string(),
    }),
  }),
  valuation: z.object({
    available: z.boolean(),
    unavailableReason: z.string().nullable(),
    currency: z.string().length(3),
    currentPrice: nullableDecimalSchema,
    weightedImpliedPrice: nullableDecimalSchema,
    premiumDiscountToWeightedValue: nullableDecimalSchema,
    marketImpliedRevenueGrowth: nullableDecimalSchema,
    marketImpliedGrowthGap: nullableDecimalSchema,
    valuationMethod: z.enum(["EV_EBITDA", "EV_REVENUE"]).nullable(),
    baseRevenueGrowth: nullableDecimalSchema,
    baseEbitdaMargin: nullableDecimalSchema,
    baseValuationMultiple: nullableDecimalSchema,
    formula: z.string(),
    caveats: z.array(z.string()),
    sensitivity: z.object({
      revenueGrowthRates: z.array(decimalValueSchema).length(5),
      valuationMultiples: z.array(decimalValueSchema).length(5),
      rows: z.array(z.object({
        revenueGrowthRate: decimalValueSchema,
        impliedPrices: z.array(decimalValueSchema).length(5),
        upsideDownside: z.array(decimalValueSchema).length(5),
      })).length(5),
    }).nullable(),
  }),
  peers: z.object({
    available: z.boolean(),
    groupKey: z.string().nullable(),
    groupLabel: z.string().nullable(),
    methodology: z.string(),
    availableCount: z.number().int().nonnegative(),
    configuredCount: z.number().int().nonnegative(),
    coverageMessage: z.string(),
    rows: z.array(z.object({
      symbol: z.string(),
      researchId: z.uuid(),
      reportVersion: z.number().int().positive(),
      target: z.boolean(),
      currentPrice: nullableDecimalSchema,
      weightedImpliedPrice: nullableDecimalSchema,
      baseCaseUpside: nullableDecimalSchema,
      revenueCagr: nullableDecimalSchema,
      operatingMargin: nullableDecimalSchema,
      dataQuality: z.number().min(0).max(1),
      asOfDate: z.iso.date(),
    })),
  }),
});

export type ResearchStatus = z.infer<typeof researchStatusSchema>;
export type ResearchAccepted = z.infer<typeof researchAcceptedSchema>;
export type ResearchItem = z.infer<typeof researchItemSchema>;
export type ResearchPage = z.infer<typeof researchPageSchema>;
export type ResearchDetail = z.infer<typeof researchDetailSchema>;
export type ResearchStatusResponse = z.infer<
  typeof researchStatusResponseSchema
>;
export type Claim = z.infer<typeof claimSchema>;
export type Evidence = z.infer<typeof evidenceSchema>;
export type CanonicalResearchReport = z.infer<
  typeof canonicalResearchReportSchema
>;
export type ReportVersionResponse = z.infer<
  typeof reportVersionResponseSchema
>;
export type ResearchInsights = z.infer<typeof researchInsightsSchema>;
