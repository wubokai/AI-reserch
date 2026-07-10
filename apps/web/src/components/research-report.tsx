"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect, useMemo, useState } from "react";

import { fetchApi, errorMessage } from "@/lib/api-client";
import {
  DEMO_DATA_NOTICE,
  RESEARCH_DISCLAIMER,
  evidencePageSchema,
  evidenceSearchResponseSchema,
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
    ? "border-emerald-300/20 text-emerald-100"
    : "border-amber-300/20 text-amber-100";
}

function ClaimCard({
  claim,
  onEvidence,
}: {
  claim: Claim;
  onEvidence: (evidenceId: string) => void;
}) {
  return (
    <article className="rounded-lg border border-[#20342b] bg-[#09130f] p-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded border px-2 py-1 text-[9px] font-semibold tracking-[0.08em] ${claimTypeStyle(claim.claimType)}`}>
          {claim.claimType}
        </span>
        <span className="text-[10px] text-[#71887d]">{claim.materiality}</span>
        <span className="text-[10px] text-[#71887d]">支持度 {percent(claim.confidence)}</span>
      </div>
      <p className="mt-3 text-sm leading-6 text-[#dce8e2]">{claim.statement}</p>
      {claim.evidenceIds.length > 0 ? (
        <div className="mt-3 flex flex-wrap gap-2" aria-label="关联 Evidence">
          {claim.evidenceIds.map((evidenceId) => (
            <button
              className="rounded border border-[#2b4439] bg-[#0e1d17] px-2 py-1 font-mono text-[10px] text-emerald-200 hover:border-emerald-300/40"
              key={evidenceId}
              onClick={() => onEvidence(evidenceId)}
              type="button"
            >
              {evidenceId}
            </button>
          ))}
        </div>
      ) : null}
      {claim.limitations.length > 0 ? (
        <ul className="mt-3 list-disc space-y-1 pl-4 text-[11px] leading-5 text-amber-100/70">
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
      <aside aria-label={`Evidence ${evidence.evidenceId}`} aria-modal="true" className="h-full w-full max-w-lg overflow-y-auto border-l border-[#294137] bg-[#09130f] p-6 shadow-2xl" role="dialog">
        <div className="flex items-start justify-between gap-5">
          <div>
            <p className="font-mono text-[10px] text-emerald-200">{evidence.evidenceId}</p>
            <h2 className="mt-2 text-lg font-semibold text-white">{evidence.title}</h2>
          </div>
          <button aria-label="关闭 Evidence" className="grid size-8 place-items-center rounded border border-[#294137] text-[#91a69c] hover:text-white" onClick={onClose} type="button">×</button>
        </div>
        <p className="mt-5 text-sm leading-6 text-[#a6b8af]">{evidence.summary}</p>
        <dl className="mt-6 grid grid-cols-[130px_minmax(0,1fr)] gap-x-4 gap-y-3 text-xs">
          <dt className="text-[#647b70]">Evidence 类型</dt><dd className="text-[#dce8e2]">{evidence.evidenceType}</dd>
          <dt className="text-[#647b70]">来源</dt><dd className="text-[#dce8e2]">{evidence.sourceName}</dd>
          <dt className="text-[#647b70]">来源类型</dt><dd className="text-[#dce8e2]">{evidence.sourceType}</dd>
          <dt className="text-[#647b70]">有效日期</dt><dd className="text-[#dce8e2]">{evidence.effectiveDate ?? "未提供"}</dd>
          <dt className="text-[#647b70]">发布时间</dt><dd className="text-[#dce8e2]">{evidence.publishedAt ?? "未提供"}</dd>
          <dt className="text-[#647b70]">抓取时间</dt><dd className="text-[#dce8e2]">{evidence.retrievedAt}</dd>
          <dt className="text-[#647b70]">新鲜度</dt><dd className="text-[#dce8e2]">{evidence.freshnessStatus}</dd>
          <dt className="text-[#647b70]">质量分</dt><dd className="text-[#dce8e2]">{percent(evidence.qualityScore)}</dd>
          <dt className="text-[#647b70]">主来源</dt><dd className="text-[#dce8e2]">{evidence.isPrimarySource ? "是" : "否"}</dd>
          <dt className="text-[#647b70]">快照 Schema</dt><dd className="break-all text-[#dce8e2]">{evidence.sourceSchemaVersion ?? "内部计算"}</dd>
          <dt className="text-[#647b70]">关联 Claim</dt><dd className="break-all text-[#dce8e2]">{evidence.relatedClaimIds.join(", ") || "暂无"}</dd>
        </dl>
        {evidence.sourceUrl ? <a className="mt-5 inline-block text-xs text-emerald-200 underline" href={evidence.sourceUrl} rel="noreferrer" target="_blank">打开来源快照地址</a> : null}
        {evidence.value !== undefined ? <pre className="mt-6 overflow-x-auto rounded-lg border border-[#20342b] bg-[#07100d] p-4 text-[11px] leading-5 text-[#a6b8af]">{JSON.stringify(evidence.value, null, 2)}</pre> : null}
        <p className="mt-6 break-all font-mono text-[9px] leading-4 text-[#53695f]">SHA-256 {evidence.rawDataHash}</p>
        {evidence.normalizedDataHash ? <p className="mt-2 break-all font-mono text-[9px] leading-4 text-[#53695f]">Normalized SHA-256 {evidence.normalizedDataHash}</p> : null}
        <p className="mt-6 rounded border border-amber-300/15 bg-amber-300/[0.04] p-3 text-[11px] text-amber-100/70">{DEMO_DATA_NOTICE}</p>
      </aside>
    </div>
  );
}

export function ResearchReport({ researchId, version }: { researchId: string; version: number }) {
  const [selectedEvidenceId, setSelectedEvidenceId] = useState<string | null>(null);
  const [evidenceQuery, setEvidenceQuery] = useState("");
  const report = useQuery({
    queryKey: ["research", researchId, "reports", version],
    queryFn: () => fetchApi(`/api/research/${researchId}/reports/${version}`, reportVersionResponseSchema),
  });
  const evidence = useQuery({
    queryKey: ["research", researchId, "evidence"],
    queryFn: () => fetchApi(`/api/research/${researchId}/evidence?size=100`, evidencePageSchema),
  });
  const evidenceById = useMemo(
    () => new Map(evidence.data?.items.map((item) => [item.evidenceId, item]) ?? []),
    [evidence.data],
  );
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
    return <div className="rounded-xl border border-[#20342b] bg-[#0c1713] p-8 text-sm text-[#849b90]">正在读取不可变报告与 Evidence…</div>;
  }
  if (report.isError || evidence.isError || !report.data || !evidence.data) {
    return <div className="rounded-xl border border-rose-300/20 bg-rose-300/[0.05] p-8 text-sm text-rose-100" role="alert">{errorMessage(report.error ?? evidence.error)}</div>;
  }

  const metadata = report.data;
  const document = metadata.report;
  const currency = document.scenarioAnalysis.currency ?? "USD";
  const evidenceCount = evidence.data.items.length;

  return (
    <div className="space-y-6">
      <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5 sm:p-7">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-start lg:justify-between">
          <div className="max-w-3xl">
            <div className="flex flex-wrap items-center gap-2">
              <span className="rounded border border-emerald-300/20 bg-emerald-300/[0.06] px-2 py-1 text-xs font-bold text-emerald-100">{document.symbol}</span>
              <span className="rounded border border-[#294137] px-2 py-1 text-[10px] text-[#90a99d]">REPORT v{metadata.version}</span>
              <span className="rounded border border-emerald-300/20 px-2 py-1 text-[10px] text-emerald-100">{metadata.validationStatus}</span>
              <span className="rounded border border-amber-300/20 px-2 py-1 text-[10px] text-amber-100">{document.dataMode}</span>
            </div>
            <h1 className="mt-5 text-3xl font-semibold leading-tight tracking-[-0.03em] text-white">{document.title}</h1>
            <p className="mt-4 text-xs text-[#71887d]">数据截至 {document.asOfDate} · {evidenceCount} 条 Evidence · Schema {document.schemaVersion}</p>
          </div>
          <div className="grid shrink-0 grid-cols-3 gap-2">
            {(["markdown", "html", "pdf"] as const).map((format) => (
              <a
                className="rounded-lg border border-[#294137] bg-[#0a1511] px-3 py-2 text-center text-xs font-semibold uppercase text-emerald-100 hover:border-emerald-300/40"
                href={`/api/research/${researchId}/export?format=${format}&reportVersion=${version}`}
                key={format}
              >
                {format}
              </a>
            ))}
          </div>
        </div>
        <p className="mt-6 rounded-lg border border-amber-300/20 bg-amber-300/[0.05] px-4 py-3 text-xs font-semibold tracking-[0.06em] text-amber-100">{DEMO_DATA_NOTICE}</p>
      </section>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-6">
          {document.sections.map((section) => (
            <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5 sm:p-6" key={section.id}>
              <div className="border-b border-[#1b2c25] pb-4">
                <p className="text-[10px] font-semibold uppercase tracking-[0.16em] text-emerald-200/70">{section.id}</p>
                <h2 className="mt-2 text-xl font-semibold text-white">{section.heading}</h2>
                {section.transitionText ? <p className="mt-2 text-sm leading-6 text-[#849b90]">{section.transitionText}</p> : null}
              </div>
              <div className="mt-4 space-y-3">
                {section.claims.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}
              </div>
            </section>
          ))}

          <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5 sm:p-6">
            <h2 className="text-xl font-semibold text-white">Bull / Base / Bear 情景</h2>
            <p className="mt-2 text-xs leading-5 text-[#789085]">确定性情景引擎结果，不是价格预测或目标价。</p>
            <div className="mt-5 grid gap-3 lg:grid-cols-3">
              {document.scenarioAnalysis.scenarios.map((scenario) => (
                <article className="rounded-lg border border-[#294137] bg-[#09130f] p-4" key={scenario.name}>
                  <div className="flex items-center justify-between"><h3 className="text-sm font-bold text-emerald-100">{scenario.name}</h3><span className="text-xs text-[#8da59a]">{percent(scenario.probability)}</span></div>
                  <p className="mt-4 text-2xl font-semibold text-white">{money(scenario.impliedPrice, currency)}</p>
                  <p className={`mt-1 text-xs ${Number(scenario.upsideDownside) >= 0 ? "text-emerald-200" : "text-rose-200"}`}>{percent(scenario.upsideDownside)} 情景变动</p>
                  <dl className="mt-4 grid grid-cols-2 gap-y-2 text-[11px]"><dt className="text-[#647b70]">收入增长</dt><dd className="text-right text-[#dce8e2]">{percent(scenario.revenueGrowth)}</dd><dt className="text-[#647b70]">EBITDA Margin</dt><dd className="text-right text-[#dce8e2]">{percent(scenario.targetEbitdaMargin)}</dd><dt className="text-[#647b70]">EV/EBITDA</dt><dd className="text-right text-[#dce8e2]">{decimal(scenario.evToEbitdaMultiple, { maximumFractionDigits: 1 })}×</dd><dt className="text-[#647b70]">隐含股权价值</dt><dd className="text-right text-[#dce8e2]">{money(scenario.impliedEquityValue, currency)}</dd></dl>
                </article>
              ))}
            </div>
            <div className="mt-4 rounded-lg border border-emerald-300/20 bg-emerald-300/[0.05] p-4"><p className="text-xs text-[#8da59a]">概率加权隐含值</p><p className="mt-2 text-xl font-semibold text-emerald-100">{money(document.scenarioAnalysis.weightedImpliedPrice, currency)}</p></div>
            {document.scenarioAnalysis.summaryClaims.length > 0 ? <div className="mt-4 space-y-3">{document.scenarioAnalysis.summaryClaims.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div> : null}
          </section>

          <section className="grid gap-6 lg:grid-cols-2">
            <div className="rounded-xl border border-emerald-300/15 bg-emerald-300/[0.035] p-5"><h2 className="text-lg font-semibold text-emerald-100">Bull Case</h2><div className="mt-4 space-y-3">{document.bullCase.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div></div>
            <div className="rounded-xl border border-rose-300/15 bg-rose-300/[0.025] p-5"><h2 className="text-lg font-semibold text-rose-100">Bear Case</h2><div className="mt-4 space-y-3">{document.bearCase.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div></div>
          </section>

          {document.conclusion.length > 0 ? <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5 sm:p-6"><h2 className="text-xl font-semibold text-white">结论</h2><div className="mt-4 space-y-3">{document.conclusion.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div></section> : null}
        </div>

        <aside className="space-y-5">
          <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5">
            <h2 className="text-sm font-semibold text-white">Data Quality</h2>
            <p className="mt-4 text-3xl font-semibold text-emerald-100">{percent(document.dataQuality.score)}</p>
            <p className="mt-1 text-[11px] text-[#647b70]">确定性 completeness/freshness 评分</p>
            {document.dataQuality.limitations.length > 0 ? <ul className="mt-4 list-disc space-y-2 pl-4 text-[11px] leading-5 text-amber-100/70">{document.dataQuality.limitations.map((item) => <li key={item}>{item}</li>)}</ul> : null}
          </section>
          <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5">
            <h2 className="text-sm font-semibold text-white">Evidence Registry</h2>
            <label className="mt-4 block text-[10px] text-[#71887d]" htmlFor="filing-evidence-search">检索 Filing Chunk</label>
            <input
              className="mt-2 h-9 w-full rounded border border-[#294137] bg-[#09130f] px-3 text-xs text-[#dce8e2] placeholder:text-[#52685e]"
              id="filing-evidence-search"
              maxLength={200}
              onChange={(event) => setEvidenceQuery(event.target.value)}
              placeholder="例如 supply risk"
              value={evidenceQuery}
            />
            {filingSearch.data?.items.length ? <div className="mt-3 space-y-2" aria-label="Filing 检索结果">{filingSearch.data.items.map((item) => <button className="block w-full rounded border border-emerald-300/15 bg-emerald-300/[0.03] p-3 text-left" key={item.chunkId} onClick={() => setSelectedEvidenceId(item.evidenceId)} type="button"><span className="block text-[10px] font-semibold text-emerald-100">{item.formType} · {item.sectionName}</span><span className="mt-1 block text-[10px] leading-4 text-[#8da59a]">{item.excerpt.replace(/<\/?mark>/g, "")}</span><span className="mt-2 block break-all font-mono text-[8px] text-[#53695f]">{item.citationLocator}</span></button>)}</div> : null}
            {filingSearch.isError ? <p className="mt-3 text-[10px] text-rose-200">Filing 检索暂不可用。</p> : null}
            <div className="mt-4 space-y-2">
              {evidence.data.items.map((item) => <button className="block w-full rounded-lg border border-[#20342b] bg-[#09130f] p-3 text-left hover:border-emerald-300/30" key={item.evidenceId} onClick={() => setSelectedEvidenceId(item.evidenceId)} type="button"><span className="font-mono text-[9px] text-emerald-200">{item.evidenceId}</span><span className="mt-1 block text-xs text-[#dce8e2]">{item.title}</span></button>)}
            </div>
          </section>
          <section className="rounded-xl border border-amber-300/15 bg-amber-300/[0.035] p-5"><h2 className="text-sm font-semibold text-amber-100">免责声明</h2><p className="mt-3 text-[11px] leading-5 text-amber-100/70">{document.disclaimer}</p><p className="mt-3 text-[11px] leading-5 text-amber-100/70">{RESEARCH_DISCLAIMER}</p></section>
          <Link className="block rounded-lg border border-[#294137] px-4 py-3 text-center text-xs font-semibold text-[#a6b8af] hover:text-white" href={`/research/${researchId}`}>返回任务进度</Link>
        </aside>
      </div>

      <p className="break-all rounded border border-[#20342b] bg-[#09130f] px-4 py-3 font-mono text-[9px] text-[#53695f]">Report content SHA-256: {metadata.contentHash}</p>
      {selectedEvidence ? <EvidenceDrawer evidence={selectedEvidence} onClose={() => setSelectedEvidenceId(null)} /> : null}
      {selectedEvidenceId && !selectedEvidence ? <div className="fixed bottom-5 right-5 z-40 rounded-lg border border-rose-300/20 bg-[#27110f] p-4 text-xs text-rose-100" role="alert">Evidence {selectedEvidenceId} 未在当前 Registry 中找到。<button className="ml-3 underline" onClick={() => setSelectedEvidenceId(null)} type="button">关闭</button></div> : null}
    </div>
  );
}
