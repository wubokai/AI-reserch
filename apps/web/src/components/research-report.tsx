"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect, useMemo, useState } from "react";

import { fetchApi, errorMessage } from "@/lib/api-client";
import { AiAnalysisSummary } from "@/components/ai-analysis-summary";
import { ReportCharts } from "@/components/report-charts";
import { ReportExportCenter } from "@/components/report-export-center";
import { ReportVersionNav } from "@/components/report-version-nav";
import {
  DEMO_DATA_NOTICE,
  RESEARCH_DISCLAIMER,
  evidencePageSchema,
  evidenceSearchResponseSchema,
  researchDetailSchema,
  reportVersionResponseSchema,
  type Claim,
  type Evidence,
} from "@/lib/schemas";

function decimal(value: number | string, options?: Intl.NumberFormatOptions) {
  return new Intl.NumberFormat("zh-CN", options).format(Number(value));
}

function percent(value: number | string) {
  return decimal(Number(value), { style: "percent", maximumFractionDigits: 1 });
}

function money(value: number | string, currency: string) {
  return decimal(value, {
    style: "currency",
    currency,
    maximumFractionDigits: 2,
  });
}

function claimTypeStyle(type: Claim["claimType"]) {
  return type === "FACT" || type === "CALCULATION"
    ? "bg-emerald-100 text-emerald-700"
    : "bg-amber-100 text-amber-800";
}

const claimTypeLabels: Record<Claim["claimType"], string> = {
  FACT: "已验证事实",
  CALCULATION: "量化计算",
  INFERENCE: "分析判断",
  OPINION: "观点判断",
};

const evidenceTypeLabels: Record<Evidence["evidenceType"], string> = {
  MARKET_PRICE: "市场行情",
  FINANCIAL_METRIC: "财务数据",
  SEC_FILING: "公司公告",
  MACRO_OBSERVATION: "宏观数据",
  QUANT_RESULT: "量化计算结果",
  COMPANY_PROFILE: "公司资料",
  NEWS_ARTICLE: "新闻信息",
  OTHER: "其他依据",
};

const freshnessLabels: Record<Evidence["freshnessStatus"], string> = {
  FRESH: "数据较新",
  STALE: "可能需要更新",
  VERY_STALE: "数据已明显过期",
  UNKNOWN: "更新时间未知",
};

const scenarioLabels = { BULL: "乐观情景", BASE: "基准情景", BEAR: "谨慎情景" } as const;
const validationLabels = {
  PENDING: "等待校验",
  PASSED: "校验通过",
  PASSED_WITH_WARNINGS: "校验通过（有提示）",
  FAILED: "校验失败",
} as const;
const dataModeLabels = { REAL: "真实数据", MOCK: "演示数据", MIXED_TEST: "测试数据" } as const;

function sourceTypeLabel(sourceType: string) {
  const labels: Record<string, string> = {
    GOVERNMENT_DATA: "政府/公共数据库",
    INTERNAL_CALCULATION: "系统量化计算",
    EXCHANGE_DATA: "交易所数据",
    COMPANY_FILING: "公司官方公告",
    SEC_FILING: "监管机构公告",
    MARKET_DATA: "市场行情服务",
    MOCK: "演示数据",
  };
  return labels[sourceType] ?? "专业数据服务";
}

function qualityLabel(score: number) {
  if (score >= 0.9) return "可靠度高";
  if (score >= 0.75) return "可靠度良好";
  return "建议谨慎核对";
}

function ClaimCard({
  claim,
  onEvidence,
}: {
  claim: Claim;
  onEvidence: (evidenceId: string) => void;
}) {
  return (
    <article className="rounded-xl bg-slate-50 p-4 transition-colors hover:bg-slate-100/80">
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded-full px-2.5 py-1 text-[9px] font-semibold tracking-[0.08em] ${claimTypeStyle(claim.claimType)}`}>
          {claimTypeLabels[claim.claimType]}
        </span>
        <span className="text-[10px] text-[#64748b]">{claim.materiality === "MATERIAL" ? "关键结论" : "补充信息"}</span>
        <span className="text-[10px] text-[#64748b]">可信度 {percent(claim.confidence)}</span>
      </div>
      <p className="mt-3 text-sm leading-6 text-[#1f2937]">{claim.statement}</p>
      {claim.evidenceIds.length > 0 ? (
        <div className="mt-3 flex flex-wrap gap-2" aria-label="结论依据">
          {claim.evidenceIds.map((evidenceId, index) => (
            <button
              aria-label={`查看依据 ${index + 1}：${evidenceId}`}
              className="rounded-full bg-white px-2.5 py-1 text-[10px] font-semibold text-emerald-700 shadow-sm hover:-translate-y-0.5 hover:shadow"
              key={evidenceId}
              onClick={() => onEvidence(evidenceId)}
              type="button"
            >
              查看依据 {index + 1}
            </button>
          ))}
        </div>
      ) : null}
      {claim.limitations.length > 0 ? (
        <ul className="mt-3 list-disc space-y-1 pl-4 text-[11px] leading-5 text-amber-700">
          {claim.limitations.map((limitation) => <li key={limitation}>{limitation}</li>)}
        </ul>
      ) : null}
    </article>
  );
}

function EvidenceDrawer({ evidence, onClose }: { evidence: Evidence; onClose: () => void }) {
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-black/55" onMouseDown={(event) => {
      if (event.currentTarget === event.target) onClose();
    }}>
      <aside aria-label={`Evidence ${evidence.evidenceId}`} aria-modal="true" className="drawer-enter h-full w-full max-w-lg overflow-y-auto bg-white p-6 shadow-2xl" role="dialog">
        <div className="flex items-start justify-between gap-5">
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-[0.16em] text-emerald-600">结论依据</p>
            <h2 className="mt-2 text-lg font-semibold text-slate-950">{evidence.title}</h2>
          </div>
          <button aria-label="关闭 Evidence" className="grid size-8 place-items-center rounded-full bg-slate-100 text-slate-500 hover:rotate-3 hover:bg-slate-200 hover:text-slate-950" onClick={onClose} type="button">×</button>
        </div>
        <p className="mt-5 text-sm leading-6 text-[#475569]">{evidence.summary}</p>
        <dl className="mt-6 grid grid-cols-[100px_minmax(0,1fr)] gap-x-4 gap-y-3 text-xs">
          <dt className="text-[#64748b]">依据类型</dt><dd className="text-[#1f2937]">{evidenceTypeLabels[evidence.evidenceType]}</dd>
          <dt className="text-[#64748b]">来自哪里</dt><dd className="text-[#1f2937]">{evidence.sourceName} · {sourceTypeLabel(evidence.sourceType)}</dd>
          <dt className="text-[#64748b]">对应日期</dt><dd className="text-[#1f2937]">{evidence.effectiveDate ?? "未提供"}</dd>
          <dt className="text-[#64748b]">是否新鲜</dt><dd className="text-[#1f2937]">{freshnessLabels[evidence.freshnessStatus]}</dd>
          <dt className="text-[#64748b]">可信程度</dt><dd className="text-[#1f2937]">{qualityLabel(evidence.qualityScore)}（{percent(evidence.qualityScore)}）</dd>
          <dt className="text-[#64748b]">官方一手</dt><dd className="text-[#1f2937]">{evidence.isPrimarySource ? "是" : "否"}</dd>
        </dl>
        {evidence.attribution ? <p className="soft-section mt-5 p-3 text-[11px] leading-5 text-slate-500">来源说明：{evidence.attribution}</p> : null}
        {evidence.sourceUrl ? <a className="mt-5 inline-block text-xs font-semibold text-emerald-600 underline" href={evidence.sourceUrl} rel="noreferrer" target="_blank">查看原始官方资料</a> : null}
        <details className="soft-section mt-6 p-4 text-[11px] text-slate-500">
          <summary className="cursor-pointer font-semibold text-[#334155]">专业审计信息</summary>
          <dl className="mt-4 grid grid-cols-[100px_minmax(0,1fr)] gap-x-3 gap-y-3">
            <dt>依据编号</dt><dd className="break-all font-mono">{evidence.evidenceId}</dd>
            <dt>发布时间</dt><dd>{evidence.publishedAt ?? "未提供"}</dd>
            <dt>系统获取时间</dt><dd>{evidence.retrievedAt}</dd>
            <dt>许可策略</dt><dd className="break-all">{evidence.licensePolicyVersion ?? "未提供"}</dd>
            <dt>数据结构</dt><dd className="break-all">{evidence.sourceSchemaVersion ?? "内部计算"}</dd>
            <dt>关联结论</dt><dd className="break-all">{evidence.relatedClaimIds.join(", ") || "暂无"}</dd>
            <dt>原始校验值</dt><dd className="break-all font-mono">{evidence.rawDataHash}</dd>
            {evidence.normalizedDataHash ? <><dt>标准化校验值</dt><dd className="break-all font-mono">{evidence.normalizedDataHash}</dd></> : null}
          </dl>
          {evidence.value !== undefined ? <pre className="mt-4 overflow-x-auto rounded-lg bg-slate-100 p-3 text-[10px] leading-5">{JSON.stringify(evidence.value, null, 2)}</pre> : null}
        </details>
        {evidence.isDemoData ? <p className="mt-6 rounded border border-amber-200 bg-amber-50 p-3 text-[11px] text-amber-700">{DEMO_DATA_NOTICE}</p> : null}
      </aside>
    </div>
  );
}

export function ResearchReport({ researchId, version }: { researchId: string; version: number }) {
  const [selectedEvidenceId, setSelectedEvidenceId] = useState<string | null>(null);
  const [evidenceQuery, setEvidenceQuery] = useState("");
  const [showAllEvidence, setShowAllEvidence] = useState(false);
  const report = useQuery({
    queryKey: ["research", researchId, "reports", version],
    queryFn: () => fetchApi(`/api/research/${researchId}/reports/${version}`, reportVersionResponseSchema),
  });
  const evidence = useQuery({
    queryKey: ["research", researchId, "evidence"],
    queryFn: () => fetchApi(`/api/research/${researchId}/evidence?size=100`, evidencePageSchema),
  });
  const detail = useQuery({
    queryKey: ["research", researchId, "detail"],
    queryFn: () => fetchApi(`/api/research/${researchId}`, researchDetailSchema),
  });
  const evidenceById = useMemo(
    () => new Map(evidence.data?.items.map((item) => [item.evidenceId, item]) ?? []),
    [evidence.data],
  );
  const sourceAttributions = useMemo(() => {
    const unique = new Map<string, Evidence>();
    for (const item of evidence.data?.items ?? []) {
      if (!item.sourceSnapshotId || (!item.attribution && !item.licensePolicyVersion)) continue;
      const key = [
        item.sourceName,
        item.sourceUrl ?? "",
        item.attribution ?? "",
        item.licensePolicyVersion ?? "",
      ].join("\u0000");
      if (!unique.has(key)) unique.set(key, item);
    }
    return [...unique.values()].sort((left, right) =>
      left.sourceName.localeCompare(right.sourceName),
    );
  }, [evidence.data]);
  const filingSearch = useQuery({
    queryKey: ["research", researchId, "evidence-search", evidenceQuery.trim()],
    queryFn: () => fetchApi(
      `/api/research/${researchId}/evidence/search?q=${encodeURIComponent(evidenceQuery.trim())}&limit=10`,
      evidenceSearchResponseSchema,
    ),
    enabled: evidenceQuery.trim().length >= 2,
  });
  const selectedEvidence = selectedEvidenceId ? evidenceById.get(selectedEvidenceId) : undefined;

  if (report.isPending || evidence.isPending) {
    return <div className="surface-card p-8 text-sm text-[#64748b]">正在读取不可变报告与 Evidence…</div>;
  }
  if (report.isError || evidence.isError || !report.data || !evidence.data) {
    return <div className="rounded-xl border border-rose-200 bg-rose-50 p-8 text-sm text-rose-700" role="alert">{errorMessage(report.error ?? evidence.error)}</div>;
  }

  const metadata = report.data;
  const document = metadata.report;
  const currency = document.scenarioAnalysis.currency ?? "USD";
  const evidenceCount = evidence.data.items.length;
  const visibleEvidence = showAllEvidence
    ? evidence.data.items
    : evidence.data.items.slice(0, 8);

  return (
    <div className="space-y-6">
      <section className="surface-card p-5 sm:p-7">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-start lg:justify-between">
          <div className="max-w-3xl">
            <div className="flex flex-wrap items-center gap-2">
              <span className="rounded-lg bg-emerald-100 px-2.5 py-1 text-xs font-bold text-emerald-700">{document.symbol}</span>
              <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[10px] text-slate-500">报告版本 {metadata.version}</span>
              <span className="rounded-full bg-emerald-50 px-2.5 py-1 text-[10px] text-emerald-700">{validationLabels[metadata.validationStatus]}</span>
              <span className="rounded-full bg-amber-50 px-2.5 py-1 text-[10px] text-amber-800">{dataModeLabels[document.dataMode]}</span>
            </div>
            <h1 className="mt-5 text-3xl font-bold leading-tight tracking-[-0.035em] text-slate-950">{document.title}</h1>
            <p className="mt-4 text-xs text-[#64748b]">数据截至 {document.asOfDate} · {evidenceCount} 条可核查依据</p>
          </div>
          <ReportExportCenter dataMode={document.dataMode} researchId={researchId} symbol={document.symbol} version={version} />
        </div>
        <div className="mt-5 pt-2"><ReportVersionNav currentVersion={version} researchId={researchId} /></div>
        {document.dataMode !== "REAL" ? <p className="mt-6 rounded-xl bg-amber-50 px-4 py-3 text-xs font-semibold tracking-[0.06em] text-amber-800">{DEMO_DATA_NOTICE}</p> : null}
        {detail.data?.warnings.length ? <div className="mt-6 rounded-xl bg-amber-50 p-4 text-xs text-amber-800"><p className="font-semibold">本报告的数据范围提示</p><ul className="mt-2 space-y-2 leading-5">{detail.data.warnings.map((warning) => <li key={`${warning.code}-${warning.message}`}>• {warning.message}</li>)}</ul></div> : null}
      </section>

      <AiAnalysisSummary report={document} />

      <ReportCharts report={document} />

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-6">
          {document.sections.map((section) => (
            <section className="surface-card p-5 sm:p-6" key={section.id}>
              <div className="pb-2">
                <h2 className="mt-2 text-xl font-semibold text-slate-950">{section.heading}</h2>
                {section.transitionText ? <p className="mt-2 text-sm leading-6 text-[#64748b]">{section.transitionText}</p> : null}
              </div>
              <div className="mt-4 space-y-3">
                {section.claims.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}
              </div>
            </section>
          ))}

          <section className="surface-card p-5 sm:p-6">
            <h2 className="text-xl font-semibold text-slate-950">未来三种可能情景</h2>
            <p className="mt-2 text-xs leading-5 text-[#64748b]">把未来拆成乐观、基准和谨慎三种情况，帮助理解机会与下行风险；不是价格承诺。</p>
            {document.scenarioAnalysis.currentPrice !== undefined ? <p className="mt-4 text-sm text-[#475569]">当前市场价格：<span className="font-semibold text-slate-950">{money(document.scenarioAnalysis.currentPrice, currency)}</span></p> : null}
            <div className="mt-5 grid gap-3 lg:grid-cols-3">
              {document.scenarioAnalysis.scenarios.map((scenario) => (
                <article className={`rounded-xl p-4 transition-transform hover:-translate-y-1 ${scenario.name === "BULL" ? "bg-emerald-50" : scenario.name === "BEAR" ? "bg-rose-50" : "bg-blue-50"}`} key={scenario.name}>
                  <div className="flex items-center justify-between"><h3 className="text-sm font-bold text-emerald-700">{scenarioLabels[scenario.name]}</h3><span className="text-xs text-[#64748b]">发生权重 {percent(scenario.probability)}</span></div>
                  <p className="mt-4 text-2xl font-semibold text-slate-950">{money(scenario.impliedPrice, currency)}</p>
                  <p className={`mt-1 text-xs ${Number(scenario.upsideDownside) >= 0 ? "text-emerald-600" : "text-rose-600"}`}>可能变动 {percent(scenario.upsideDownside)}</p>
                  <dl className="mt-4 grid grid-cols-2 gap-y-2 text-[11px]"><dt className="text-[#64748b]">收入增长</dt><dd className="text-right text-[#1f2937]">{percent(scenario.revenueGrowth)}</dd><dt className="text-[#64748b]">EBITDA 利润率</dt><dd className="text-right text-[#1f2937]">{percent(scenario.targetEbitdaMargin)}</dd><dt className="text-[#64748b]">估值方法</dt><dd className="text-right text-[#1f2937]">{scenario.valuationMethod === "EV_REVENUE" ? "EV/收入" : "EV/EBITDA"}</dd><dt className="text-[#64748b]">估值倍数</dt><dd className="text-right text-[#1f2937]">{decimal(scenario.valuationMultiple ?? scenario.evToEbitdaMultiple, { maximumFractionDigits: 1 })}×</dd><dt className="text-[#64748b]">公司股权价值</dt><dd className="text-right text-[#1f2937]">{money(scenario.impliedEquityValue, currency)}</dd></dl>
                </article>
              ))}
            </div>
            <div className="mt-4 rounded-xl bg-emerald-50 p-4"><p className="text-xs text-slate-500">综合三种情况后的参考值</p><p className="mt-2 text-xl font-bold text-emerald-700">{money(document.scenarioAnalysis.weightedImpliedPrice, currency)}</p></div>
            {document.scenarioAnalysis.summaryClaims.length > 0 ? <div className="mt-4 space-y-3">{document.scenarioAnalysis.summaryClaims.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div> : null}
          </section>

          <section className="grid gap-6 lg:grid-cols-2">
            <div className="rounded-2xl bg-emerald-50 p-5"><h2 className="text-lg font-semibold text-emerald-700">可能上涨的理由</h2><div className="mt-4 space-y-3">{document.bullCase.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div></div>
            <div className="rounded-2xl bg-rose-50 p-5"><h2 className="text-lg font-semibold text-rose-700">可能下跌的风险</h2><div className="mt-4 space-y-3">{document.bearCase.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div></div>
          </section>

          {document.conclusion.length > 0 ? <section className="surface-card p-5 sm:p-6"><h2 className="text-xl font-semibold text-slate-950">结论</h2><div className="mt-4 space-y-3">{document.conclusion.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div></section> : null}
        </div>

        <aside className="space-y-5">
          {sourceAttributions.length > 0 ? (
            <section className="surface-card p-5">
              <h2 className="text-sm font-semibold text-slate-950">这些数据来自哪里</h2>
              <p className="mt-2 text-[11px] leading-5 text-[#64748b]">优先展示官方和一手来源，专业许可信息已收起。</p>
              <div className="mt-4 space-y-2">
                {sourceAttributions.map((item) => (
                  <article className="rounded-xl bg-slate-50 p-3" key={[item.sourceName, item.sourceUrl, item.attribution].join("|")}>
                    <p className="text-xs font-semibold text-[#1f2937]">{item.sourceName}</p>
                    <p className="mt-1 text-[10px] text-[#64748b]">{sourceTypeLabel(item.sourceType)} · {qualityLabel(item.qualityScore)}</p>
                    {item.attribution ? <p className="mt-2 text-[11px] leading-5 text-[#475569]">{item.attribution}</p> : null}
                    {item.sourceUrl ? <a className="mt-2 inline-block text-[10px] text-emerald-600 underline" href={item.sourceUrl} rel="noreferrer" target="_blank">查看官方资料</a> : null}
                    {item.licensePolicyVersion ? <details className="mt-2 text-[9px] text-[#64748b]"><summary className="cursor-pointer">许可与使用说明</summary><p className="mt-1 break-all font-mono">{item.licensePolicyVersion}</p></details> : null}
                  </article>
                ))}
              </div>
            </section>
          ) : null}
          <section className="surface-card p-5">
            <h2 className="text-sm font-semibold text-slate-950">数据可靠度</h2>
            <p className="mt-4 text-3xl font-semibold text-emerald-700">{percent(document.dataQuality.score)}</p>
            <p className="mt-1 text-[11px] text-[#64748b]">综合数据完整性、更新时间和来源一致性</p>
            <dl className="mt-4 space-y-3 text-[11px]"><div><dt className="text-[#64748b]">还缺什么</dt><dd className="mt-1 text-[#1f2937]">{document.dataQuality.missingData.join("、") || "没有发现关键缺失"}</dd></div><div><dt className="text-[#64748b]">是否过期</dt><dd className="mt-1 break-all text-[#1f2937]">{document.dataQuality.staleEvidenceIds.length ? `有 ${document.dataQuality.staleEvidenceIds.length} 条需要更新` : "没有发现过期数据"}</dd></div><div><dt className="text-[#64748b]">来源是否矛盾</dt><dd className="mt-1 text-[#1f2937]">{document.dataQuality.sourceConflicts.join("、") || "没有发现明显冲突"}</dd></div></dl>
            {document.dataQuality.limitations.length > 0 ? <details className="mt-4 rounded-xl bg-amber-50 p-3 text-[11px] text-amber-700"><summary className="cursor-pointer font-semibold">查看 {document.dataQuality.limitations.length} 条数据限制</summary><ul className="mt-3 list-disc space-y-2 pl-4 leading-5">{document.dataQuality.limitations.map((item) => <li key={item}>{item}</li>)}</ul></details> : null}
          </section>
          <section className="surface-card p-5">
            <h2 className="text-sm font-semibold text-slate-950">报告依据（{evidenceCount} 条）</h2>
            <p className="mt-2 text-[11px] leading-5 text-[#64748b]">点击即可查看这条数据支持了什么结论。</p>
            <label className="mt-4 block text-[10px] text-[#64748b]" htmlFor="filing-evidence-search">搜索公司公告内容</label>
            <input
              className="mt-2 h-9 w-full rounded border border-[#cbd5e1] bg-[#f8fafc] px-3 text-xs text-[#1f2937] placeholder:text-[#94a3b8]"
              id="filing-evidence-search"
              maxLength={200}
              onChange={(event) => setEvidenceQuery(event.target.value)}
              placeholder="例如：供应链风险"
              value={evidenceQuery}
            />
            {filingSearch.data?.items.length ? <div className="mt-3 space-y-2" aria-label="Filing 检索结果">{filingSearch.data.items.map((item) => <button className="block w-full rounded-xl bg-emerald-50 p-3 text-left hover:-translate-y-0.5" key={item.chunkId} onClick={() => setSelectedEvidenceId(item.evidenceId)} type="button"><span className="block text-[10px] font-semibold text-emerald-700">{item.formType} · {item.sectionName}</span><span className="mt-1 block text-[10px] leading-4 text-[#64748b]">{item.excerpt.replace(/<\/?mark>/g, "")}</span><span className="mt-2 block break-all font-mono text-[8px] text-[#94a3b8]">{item.citationLocator}</span></button>)}</div> : null}
            {filingSearch.isError ? <p className="mt-3 text-[10px] text-rose-600">Filing 检索暂不可用。</p> : null}
            <div className="mt-4 space-y-2">
              {visibleEvidence.map((item) => <button className="block w-full rounded-xl bg-slate-50 p-3 text-left hover:-translate-y-0.5 hover:bg-slate-100" key={item.evidenceId} onClick={() => setSelectedEvidenceId(item.evidenceId)} type="button"><span className="text-[9px] font-semibold text-emerald-600">{evidenceTypeLabels[item.evidenceType]} · {freshnessLabels[item.freshnessStatus]}</span><span className="mt-1 block text-xs text-[#1f2937]">{item.title}</span><span className="mt-1 block text-[10px] text-[#64748b]">来源：{item.sourceName}</span></button>)}
            </div>
            {evidenceCount > 8 ? <button className="mt-3 w-full rounded-xl bg-slate-100 px-3 py-2 text-[11px] font-semibold text-slate-600 hover:bg-slate-200 hover:text-slate-950" onClick={() => setShowAllEvidence((current) => !current)} type="button">{showAllEvidence ? "收起依据" : `查看全部 ${evidenceCount} 条依据`}</button> : null}
          </section>
          <section className="rounded-2xl bg-amber-50 p-5"><h2 className="text-sm font-semibold text-amber-800">免责声明</h2><p className="mt-3 text-[11px] leading-5 text-amber-700">{document.disclaimer}</p><p className="mt-3 text-[11px] leading-5 text-amber-700">{RESEARCH_DISCLAIMER}</p></section>
          <Link className="block rounded-xl bg-slate-100 px-4 py-3 text-center text-xs font-semibold text-slate-600 hover:bg-slate-200 hover:text-slate-950" href={`/research/${researchId}`}>返回任务进度</Link>
        </aside>
      </div>

      <p className="break-all px-1 font-mono text-[9px] text-slate-400">Report content SHA-256: {metadata.contentHash}</p>
      {selectedEvidence ? <EvidenceDrawer evidence={selectedEvidence} onClose={() => setSelectedEvidenceId(null)} /> : null}
      {selectedEvidenceId && !selectedEvidence ? <div className="fixed bottom-5 right-5 z-40 rounded-lg border border-rose-200 bg-[#fff1f2] p-4 text-xs text-rose-700" role="alert">Evidence {selectedEvidenceId} 未在当前 Registry 中找到。<button className="ml-3 underline" onClick={() => setSelectedEvidenceId(null)} type="button">关闭</button></div> : null}
    </div>
  );
}
