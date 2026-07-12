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
    <article className="rounded-lg border border-[#20342b] bg-[#09130f] p-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className={`rounded border px-2 py-1 text-[9px] font-semibold tracking-[0.08em] ${claimTypeStyle(claim.claimType)}`}>
          {claimTypeLabels[claim.claimType]}
        </span>
        <span className="text-[10px] text-[#71887d]">{claim.materiality === "MATERIAL" ? "关键结论" : "补充信息"}</span>
        <span className="text-[10px] text-[#71887d]">可信度 {percent(claim.confidence)}</span>
      </div>
      <p className="mt-3 text-sm leading-6 text-[#dce8e2]">{claim.statement}</p>
      {claim.evidenceIds.length > 0 ? (
        <div className="mt-3 flex flex-wrap gap-2" aria-label="结论依据">
          {claim.evidenceIds.map((evidenceId, index) => (
            <button
              aria-label={`查看依据 ${index + 1}：${evidenceId}`}
              className="rounded border border-[#2b4439] bg-[#0e1d17] px-2 py-1 text-[10px] font-semibold text-emerald-200 hover:border-emerald-300/40"
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
            <p className="text-[10px] font-semibold uppercase tracking-[0.16em] text-emerald-200">结论依据</p>
            <h2 className="mt-2 text-lg font-semibold text-white">{evidence.title}</h2>
          </div>
          <button aria-label="关闭 Evidence" className="grid size-8 place-items-center rounded border border-[#294137] text-[#91a69c] hover:text-white" onClick={onClose} type="button">×</button>
        </div>
        <p className="mt-5 text-sm leading-6 text-[#a6b8af]">{evidence.summary}</p>
        <dl className="mt-6 grid grid-cols-[100px_minmax(0,1fr)] gap-x-4 gap-y-3 text-xs">
          <dt className="text-[#647b70]">依据类型</dt><dd className="text-[#dce8e2]">{evidenceTypeLabels[evidence.evidenceType]}</dd>
          <dt className="text-[#647b70]">来自哪里</dt><dd className="text-[#dce8e2]">{evidence.sourceName} · {sourceTypeLabel(evidence.sourceType)}</dd>
          <dt className="text-[#647b70]">对应日期</dt><dd className="text-[#dce8e2]">{evidence.effectiveDate ?? "未提供"}</dd>
          <dt className="text-[#647b70]">是否新鲜</dt><dd className="text-[#dce8e2]">{freshnessLabels[evidence.freshnessStatus]}</dd>
          <dt className="text-[#647b70]">可信程度</dt><dd className="text-[#dce8e2]">{qualityLabel(evidence.qualityScore)}（{percent(evidence.qualityScore)}）</dd>
          <dt className="text-[#647b70]">官方一手</dt><dd className="text-[#dce8e2]">{evidence.isPrimarySource ? "是" : "否"}</dd>
        </dl>
        {evidence.attribution ? <p className="mt-5 rounded-lg border border-[#20342b] bg-[#07100d] p-3 text-[11px] leading-5 text-[#8da59a]">来源说明：{evidence.attribution}</p> : null}
        {evidence.sourceUrl ? <a className="mt-5 inline-block text-xs font-semibold text-emerald-200 underline" href={evidence.sourceUrl} rel="noreferrer" target="_blank">查看原始官方资料</a> : null}
        <details className="mt-6 rounded-lg border border-[#20342b] bg-[#07100d] p-4 text-[11px] text-[#8da59a]">
          <summary className="cursor-pointer font-semibold text-[#b8c8c0]">专业审计信息</summary>
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
          {evidence.value !== undefined ? <pre className="mt-4 overflow-x-auto rounded border border-[#20342b] bg-[#050b09] p-3 text-[10px] leading-5">{JSON.stringify(evidence.value, null, 2)}</pre> : null}
        </details>
        {evidence.isDemoData ? <p className="mt-6 rounded border border-amber-300/15 bg-amber-300/[0.04] p-3 text-[11px] text-amber-100/70">{DEMO_DATA_NOTICE}</p> : null}
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
    return <div className="rounded-xl border border-[#20342b] bg-[#0c1713] p-8 text-sm text-[#849b90]">正在读取不可变报告与 Evidence…</div>;
  }
  if (report.isError || evidence.isError || !report.data || !evidence.data) {
    return <div className="rounded-xl border border-rose-300/20 bg-rose-300/[0.05] p-8 text-sm text-rose-100" role="alert">{errorMessage(report.error ?? evidence.error)}</div>;
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
      <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5 sm:p-7">
        <div className="flex flex-col gap-6 lg:flex-row lg:items-start lg:justify-between">
          <div className="max-w-3xl">
            <div className="flex flex-wrap items-center gap-2">
              <span className="rounded border border-emerald-300/20 bg-emerald-300/[0.06] px-2 py-1 text-xs font-bold text-emerald-100">{document.symbol}</span>
              <span className="rounded border border-[#294137] px-2 py-1 text-[10px] text-[#90a99d]">报告版本 {metadata.version}</span>
              <span className="rounded border border-emerald-300/20 px-2 py-1 text-[10px] text-emerald-100">{validationLabels[metadata.validationStatus]}</span>
              <span className="rounded border border-amber-300/20 px-2 py-1 text-[10px] text-amber-100">{dataModeLabels[document.dataMode]}</span>
            </div>
            <h1 className="mt-5 text-3xl font-semibold leading-tight tracking-[-0.03em] text-white">{document.title}</h1>
            <p className="mt-4 text-xs text-[#71887d]">数据截至 {document.asOfDate} · {evidenceCount} 条可核查依据</p>
          </div>
          <ReportExportCenter dataMode={document.dataMode} researchId={researchId} symbol={document.symbol} version={version} />
        </div>
        <div className="mt-5 border-t border-[#1b2c25] pt-4"><ReportVersionNav currentVersion={version} researchId={researchId} /></div>
        {document.dataMode !== "REAL" ? <p className="mt-6 rounded-lg border border-amber-300/20 bg-amber-300/[0.05] px-4 py-3 text-xs font-semibold tracking-[0.06em] text-amber-100">{DEMO_DATA_NOTICE}</p> : null}
      </section>

      <AiAnalysisSummary report={document} />

      <ReportCharts report={document} />

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-6">
          {document.sections.map((section) => (
            <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5 sm:p-6" key={section.id}>
              <div className="border-b border-[#1b2c25] pb-4">
                <h2 className="mt-2 text-xl font-semibold text-white">{section.heading}</h2>
                {section.transitionText ? <p className="mt-2 text-sm leading-6 text-[#849b90]">{section.transitionText}</p> : null}
              </div>
              <div className="mt-4 space-y-3">
                {section.claims.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}
              </div>
            </section>
          ))}

          <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5 sm:p-6">
            <h2 className="text-xl font-semibold text-white">未来三种可能情景</h2>
            <p className="mt-2 text-xs leading-5 text-[#789085]">把未来拆成乐观、基准和谨慎三种情况，帮助理解机会与下行风险；不是价格承诺。</p>
            {document.scenarioAnalysis.currentPrice !== undefined ? <p className="mt-4 text-sm text-[#a9beb4]">当前市场价格：<span className="font-semibold text-white">{money(document.scenarioAnalysis.currentPrice, currency)}</span></p> : null}
            <div className="mt-5 grid gap-3 lg:grid-cols-3">
              {document.scenarioAnalysis.scenarios.map((scenario) => (
                <article className="rounded-lg border border-[#294137] bg-[#09130f] p-4" key={scenario.name}>
                  <div className="flex items-center justify-between"><h3 className="text-sm font-bold text-emerald-100">{scenarioLabels[scenario.name]}</h3><span className="text-xs text-[#8da59a]">发生权重 {percent(scenario.probability)}</span></div>
                  <p className="mt-4 text-2xl font-semibold text-white">{money(scenario.impliedPrice, currency)}</p>
                  <p className={`mt-1 text-xs ${Number(scenario.upsideDownside) >= 0 ? "text-emerald-200" : "text-rose-200"}`}>可能变动 {percent(scenario.upsideDownside)}</p>
                  <dl className="mt-4 grid grid-cols-2 gap-y-2 text-[11px]"><dt className="text-[#647b70]">收入增长</dt><dd className="text-right text-[#dce8e2]">{percent(scenario.revenueGrowth)}</dd><dt className="text-[#647b70]">EBITDA 利润率</dt><dd className="text-right text-[#dce8e2]">{percent(scenario.targetEbitdaMargin)}</dd><dt className="text-[#647b70]">估值方法</dt><dd className="text-right text-[#dce8e2]">{scenario.valuationMethod === "EV_REVENUE" ? "EV/收入" : "EV/EBITDA"}</dd><dt className="text-[#647b70]">估值倍数</dt><dd className="text-right text-[#dce8e2]">{decimal(scenario.valuationMultiple ?? scenario.evToEbitdaMultiple, { maximumFractionDigits: 1 })}×</dd><dt className="text-[#647b70]">公司股权价值</dt><dd className="text-right text-[#dce8e2]">{money(scenario.impliedEquityValue, currency)}</dd></dl>
                </article>
              ))}
            </div>
            <div className="mt-4 rounded-lg border border-emerald-300/20 bg-emerald-300/[0.05] p-4"><p className="text-xs text-[#8da59a]">综合三种情况后的参考值</p><p className="mt-2 text-xl font-semibold text-emerald-100">{money(document.scenarioAnalysis.weightedImpliedPrice, currency)}</p></div>
            {document.scenarioAnalysis.summaryClaims.length > 0 ? <div className="mt-4 space-y-3">{document.scenarioAnalysis.summaryClaims.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div> : null}
          </section>

          <section className="grid gap-6 lg:grid-cols-2">
            <div className="rounded-xl border border-emerald-300/15 bg-emerald-300/[0.035] p-5"><h2 className="text-lg font-semibold text-emerald-100">可能上涨的理由</h2><div className="mt-4 space-y-3">{document.bullCase.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div></div>
            <div className="rounded-xl border border-rose-300/15 bg-rose-300/[0.025] p-5"><h2 className="text-lg font-semibold text-rose-100">可能下跌的风险</h2><div className="mt-4 space-y-3">{document.bearCase.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div></div>
          </section>

          {document.conclusion.length > 0 ? <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5 sm:p-6"><h2 className="text-xl font-semibold text-white">结论</h2><div className="mt-4 space-y-3">{document.conclusion.map((claim) => <ClaimCard claim={claim} key={claim.id} onEvidence={setSelectedEvidenceId} />)}</div></section> : null}
        </div>

        <aside className="space-y-5">
          {sourceAttributions.length > 0 ? (
            <section className="rounded-xl border border-emerald-300/15 bg-emerald-300/[0.035] p-5">
              <h2 className="text-sm font-semibold text-emerald-100">这些数据来自哪里</h2>
              <p className="mt-2 text-[11px] leading-5 text-[#789085]">优先展示官方和一手来源，专业许可信息已收起。</p>
              <div className="mt-4 space-y-4">
                {sourceAttributions.map((item) => (
                  <article className="border-t border-emerald-300/10 pt-4 first:border-t-0 first:pt-0" key={[item.sourceName, item.sourceUrl, item.attribution].join("|")}>
                    <p className="text-xs font-semibold text-[#dce8e2]">{item.sourceName}</p>
                    <p className="mt-1 text-[10px] text-[#71887d]">{sourceTypeLabel(item.sourceType)} · {qualityLabel(item.qualityScore)}</p>
                    {item.attribution ? <p className="mt-2 text-[11px] leading-5 text-[#a6b8af]">{item.attribution}</p> : null}
                    {item.sourceUrl ? <a className="mt-2 inline-block text-[10px] text-emerald-200 underline" href={item.sourceUrl} rel="noreferrer" target="_blank">查看官方资料</a> : null}
                    {item.licensePolicyVersion ? <details className="mt-2 text-[9px] text-[#647b70]"><summary className="cursor-pointer">许可与使用说明</summary><p className="mt-1 break-all font-mono">{item.licensePolicyVersion}</p></details> : null}
                  </article>
                ))}
              </div>
            </section>
          ) : null}
          <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5">
            <h2 className="text-sm font-semibold text-white">数据可靠度</h2>
            <p className="mt-4 text-3xl font-semibold text-emerald-100">{percent(document.dataQuality.score)}</p>
            <p className="mt-1 text-[11px] text-[#647b70]">综合数据完整性、更新时间和来源一致性</p>
            <dl className="mt-4 space-y-3 text-[11px]"><div><dt className="text-[#647b70]">还缺什么</dt><dd className="mt-1 text-[#dce8e2]">{document.dataQuality.missingData.join("、") || "没有发现关键缺失"}</dd></div><div><dt className="text-[#647b70]">是否过期</dt><dd className="mt-1 break-all text-[#dce8e2]">{document.dataQuality.staleEvidenceIds.length ? `有 ${document.dataQuality.staleEvidenceIds.length} 条需要更新` : "没有发现过期数据"}</dd></div><div><dt className="text-[#647b70]">来源是否矛盾</dt><dd className="mt-1 text-[#dce8e2]">{document.dataQuality.sourceConflicts.join("、") || "没有发现明显冲突"}</dd></div></dl>
            {document.dataQuality.limitations.length > 0 ? <details className="mt-4 rounded-lg border border-amber-300/10 bg-amber-300/[0.025] p-3 text-[11px] text-amber-100/70"><summary className="cursor-pointer font-semibold">查看 {document.dataQuality.limitations.length} 条数据限制</summary><ul className="mt-3 list-disc space-y-2 pl-4 leading-5">{document.dataQuality.limitations.map((item) => <li key={item}>{item}</li>)}</ul></details> : null}
          </section>
          <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5">
            <h2 className="text-sm font-semibold text-white">报告依据（{evidenceCount} 条）</h2>
            <p className="mt-2 text-[11px] leading-5 text-[#71887d]">点击即可查看这条数据支持了什么结论。</p>
            <label className="mt-4 block text-[10px] text-[#71887d]" htmlFor="filing-evidence-search">搜索公司公告内容</label>
            <input
              className="mt-2 h-9 w-full rounded border border-[#294137] bg-[#09130f] px-3 text-xs text-[#dce8e2] placeholder:text-[#52685e]"
              id="filing-evidence-search"
              maxLength={200}
              onChange={(event) => setEvidenceQuery(event.target.value)}
              placeholder="例如：供应链风险"
              value={evidenceQuery}
            />
            {filingSearch.data?.items.length ? <div className="mt-3 space-y-2" aria-label="Filing 检索结果">{filingSearch.data.items.map((item) => <button className="block w-full rounded border border-emerald-300/15 bg-emerald-300/[0.03] p-3 text-left" key={item.chunkId} onClick={() => setSelectedEvidenceId(item.evidenceId)} type="button"><span className="block text-[10px] font-semibold text-emerald-100">{item.formType} · {item.sectionName}</span><span className="mt-1 block text-[10px] leading-4 text-[#8da59a]">{item.excerpt.replace(/<\/?mark>/g, "")}</span><span className="mt-2 block break-all font-mono text-[8px] text-[#53695f]">{item.citationLocator}</span></button>)}</div> : null}
            {filingSearch.isError ? <p className="mt-3 text-[10px] text-rose-200">Filing 检索暂不可用。</p> : null}
            <div className="mt-4 space-y-2">
              {visibleEvidence.map((item) => <button className="block w-full rounded-lg border border-[#20342b] bg-[#09130f] p-3 text-left hover:border-emerald-300/30" key={item.evidenceId} onClick={() => setSelectedEvidenceId(item.evidenceId)} type="button"><span className="text-[9px] font-semibold text-emerald-200">{evidenceTypeLabels[item.evidenceType]} · {freshnessLabels[item.freshnessStatus]}</span><span className="mt-1 block text-xs text-[#dce8e2]">{item.title}</span><span className="mt-1 block text-[10px] text-[#647b70]">来源：{item.sourceName}</span></button>)}
            </div>
            {evidenceCount > 8 ? <button className="mt-3 w-full rounded-lg border border-[#294137] px-3 py-2 text-[11px] font-semibold text-[#a6b8af] hover:border-emerald-300/30 hover:text-white" onClick={() => setShowAllEvidence((current) => !current)} type="button">{showAllEvidence ? "收起依据" : `查看全部 ${evidenceCount} 条依据`}</button> : null}
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
