import type { CanonicalResearchReport } from "@/lib/schemas";

export const phase3ResearchId = "11111111-1111-4111-8111-111111111111";
export const phase3EvidenceId = "ev_MU_RETURN_01";
export const phase3FilingEvidenceId = "ev_MU_FILING_01";

const calculationClaim = {
  id: "cl_return_01",
  statement: "Mock fixture 的五年累计收益为 42.0%。",
  claimType: "CALCULATION" as const,
  materiality: "MATERIAL" as const,
  evidenceIds: [phase3EvidenceId],
  calculationIds: ["calc_return_01"],
  numericReferences: [
    {
      token: "42.0%",
      normalizedValue: "0.42",
      unit: "ratio",
      sourceKind: "EVIDENCE" as const,
      sourceId: phase3EvidenceId,
      jsonPointer: "/value",
      tolerance: "0.0001",
    },
  ],
  dateReferences: [],
  confidence: 0.96,
  limitations: ["结果仅适用于固定 Mock fixture。"],
};

export const phase3CanonicalReport: CanonicalResearchReport = {
  schemaVersion: "research_report_v1",
  title: "MU Mock 证据研究报告",
  symbol: "MU",
  securityType: "COMMON_STOCK",
  locale: "zh-CN",
  asOfDate: "2025-12-31",
  dataMode: "MOCK",
  sections: [
    {
      id: "quantitative_snapshot",
      heading: "量化概览",
      claims: [calculationClaim],
      transitionText: "以下结果由固定行情 fixture 确定性计算。",
    },
  ],
  bullCase: [calculationClaim],
  bearCase: [{ ...calculationClaim, id: "cl_bear_01", statement: "历史波动提示下行情景仍需保留。", claimType: "INFERENCE", confidence: 0.72 }],
  catalysts: [],
  risks: [{ category: "DATA_QUALITY", claim: { ...calculationClaim, id: "cl_risk_01", statement: "Mock 数据不能代表当前市场。", claimType: "FACT" } }],
  scenarioAnalysis: {
    calculationId: "calc_scenario_01",
    currency: "USD",
    scenarios: [
      { name: "BULL", probability: "0.25", revenueGrowth: "0.18", targetEbitdaMargin: "0.34", evToEbitdaMultiple: "12", impliedEquityValue: "150000000000", impliedPrice: "145", upsideDownside: "0.30" },
      { name: "BASE", probability: "0.50", revenueGrowth: "0.10", targetEbitdaMargin: "0.28", evToEbitdaMultiple: "10", impliedEquityValue: "112000000000", impliedPrice: "108", upsideDownside: "0.02" },
      { name: "BEAR", probability: "0.25", revenueGrowth: "-0.05", targetEbitdaMargin: "0.18", evToEbitdaMultiple: "7", impliedEquityValue: "65000000000", impliedPrice: "62", upsideDownside: "-0.41" },
    ],
    weightedImpliedPrice: "105.75",
    summaryClaims: [{ ...calculationClaim, id: "cl_scenario_01", statement: "概率加权隐含情景值为 105.75 美元。" }],
  },
  dataQuality: {
    score: 0.92,
    missingData: [],
    staleEvidenceIds: [],
    sourceConflicts: [],
    limitations: ["全部输入均为固定 Mock fixture。"],
  },
  conclusion: [calculationClaim],
  disclaimer: "本报告使用演示数据，不构成投资建议。",
};

export const phase3ReportEnvelope = {
  researchId: phase3ResearchId,
  version: 1,
  validationStatus: "PASSED_WITH_WARNINGS",
  contentHash: "a".repeat(64),
  createdAt: "2026-07-10T12:00:10Z",
  report: phase3CanonicalReport,
};

export const phase3EvidencePage = {
  items: [
    {
      evidenceId: phase3EvidenceId,
      evidenceType: "QUANT_RESULT",
      title: "五年累计收益",
      summary: "由 Mock adjusted close 序列确定性计算。",
      value: "0.42",
      unit: "ratio",
      sourceName: "Mock Market Provider",
      sourceUrl: null,
      sourceType: "INTERNAL_CALCULATION",
      publishedAt: null,
      retrievedAt: "2026-07-10T12:00:05Z",
      effectiveDate: "2025-12-31",
      isPrimarySource: true,
      freshnessStatus: "FRESH",
      qualityScore: 0.96,
      rawDataHash: "b".repeat(64),
      isDemoData: true,
      relatedClaimIds: ["cl_return_01"],
      sourceSnapshotId: null,
      sourceSchemaVersion: null,
      normalizedDataHash: null,
      attribution: null,
      licensePolicyVersion: null,
    },
    {
      evidenceId: phase3FilingEvidenceId,
      evidenceType: "SEC_FILING",
      title: "Synthetic annual filing",
      summary: "Fixed filing metadata with searchable section chunks.",
      value: { asOfDate: "2025-12-31", filings: [{ formType: "10-K" }] },
      unit: null,
      sourceName: "Mock Filing Provider",
      sourceUrl: null,
      sourceType: "MOCK",
      publishedAt: null,
      retrievedAt: "2026-07-10T12:00:05Z",
      effectiveDate: "2025-12-31",
      isPrimarySource: true,
      freshnessStatus: "FRESH",
      qualityScore: 1,
      rawDataHash: "d".repeat(64),
      isDemoData: true,
      relatedClaimIds: [],
      sourceSnapshotId: "22222222-2222-4222-8222-222222222222",
      sourceSchemaVersion: "mock_filings_v1",
      normalizedDataHash: "e".repeat(64),
      attribution: null,
      licensePolicyVersion: "mock_fixture_license_v1",
    },
  ],
  page: { number: 0, size: 100, totalElements: 2, totalPages: 1, first: true, last: true },
  dataMode: "MOCK",
};

export const phase3EvidenceSearchResponse = {
  query: "supply risk",
  items: [{
    evidenceId: phase3FilingEvidenceId,
    filingId: "33333333-3333-4333-8333-333333333333",
    chunkId: "44444444-4444-4444-8444-444444444444",
    externalDocumentId: "mock-mu-10k-2025",
    formType: "10-K",
    filingDate: "2025-10-01",
    sectionName: "ITEM_1A_RISK_FACTORS",
    chunkIndex: 0,
    excerpt: "<mark>Supply</mark> concentration creates inventory <mark>risk</mark>.",
    citationLocator: "filing:mock-mu-10k-2025#ITEM_1A_RISK_FACTORS:chunk=0:chars=0-120",
    rank: 0.75,
    isDemoData: true,
  }],
  dataMode: "MOCK",
};
