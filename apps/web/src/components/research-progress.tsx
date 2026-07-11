"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";

import { fetchApi, errorMessage } from "@/lib/api-client";
import {
  RESEARCH_DISCLAIMER,
  researchAcceptedSchema,
  researchDetailSchema,
  researchStatusResponseSchema,
  type ResearchStatus,
} from "@/lib/schemas";

const terminalStatuses = new Set<ResearchStatus>([
  "COMPLETED",
  "PARTIALLY_COMPLETED",
  "FAILED",
  "CANCELLED",
]);

const statusLabels: Record<ResearchStatus, string> = {
  CREATED: "已创建",
  QUEUED: "等待执行",
  RESOLVING_SECURITY: "解析证券",
  FETCHING_MARKET_DATA: "读取行情",
  FETCHING_FUNDAMENTALS: "读取基本面",
  FETCHING_FILINGS: "读取 Filing",
  FETCHING_MACRO_DATA: "读取宏观数据",
  VALIDATING_DATA: "验证数据",
  RUNNING_QUANT_ANALYSIS: "运行量化分析",
  ANALYZING_FUNDAMENTALS: "分析基本面",
  BUILDING_EVIDENCE: "建立 Evidence",
  GENERATING_REPORT: "生成报告",
  VALIDATING_REPORT: "验证报告",
  COMPLETED: "已完成",
  PARTIALLY_COMPLETED: "部分完成",
  FAILED: "失败",
  CANCELLED: "已取消",
};

export function pollIntervalForStatus(status: ResearchStatus | undefined) {
  return status && terminalStatuses.has(status) ? false : 2_000;
}

function stepLabel(step: string) {
  return step
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function ResearchProgress({ researchId }: { researchId: string }) {
  const queryClient = useQueryClient();
  const [retryFromStep, setRetryFromStep] = useState<string>("");
  const status = useQuery({
    queryKey: ["research", researchId, "status"],
    queryFn: () => fetchApi(`/api/research/${researchId}/status`, researchStatusResponseSchema),
    refetchInterval: (query) => pollIntervalForStatus(query.state.data?.status),
  });
  const detail = useQuery({
    queryKey: ["research", researchId, "detail"],
    queryFn: () => fetchApi(`/api/research/${researchId}`, researchDetailSchema),
    refetchInterval: (query) => pollIntervalForStatus(query.state.data?.status),
  });
  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["research", researchId] }),
      queryClient.invalidateQueries({ queryKey: ["research", "recent"] }),
    ]);
  };
  const cancel = useMutation({
    mutationFn: () => fetchApi(`/api/research/${researchId}/cancel`, researchStatusResponseSchema, {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify({ reason: "USER_REQUESTED_FROM_WEB" }),
    }),
    onSuccess: refresh,
  });
  const retry = useMutation({
    mutationFn: (fromStep: string) => fetchApi(`/api/research/${researchId}/retry`, researchAcceptedSchema, {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify({ ...(fromStep ? { fromStep } : {}), reason: "USER_REQUESTED_FROM_WEB" }),
    }),
    onSuccess: refresh,
  });

  if (status.isPending || detail.isPending) {
    return <div className="rounded-xl border border-[#20342b] bg-[#0c1713] p-8 text-sm text-[#849b90]">正在读取任务状态…</div>;
  }
  if (status.isError || detail.isError || !status.data || !detail.data) {
    return <div className="rounded-xl border border-rose-300/20 bg-rose-300/[0.05] p-8 text-sm text-rose-100" role="alert">{errorMessage(status.error ?? detail.error)}</div>;
  }

  const snapshot = status.data;
  const research = detail.data;
  const terminal = terminalStatuses.has(snapshot.status);
  const canCancel = !terminal && !snapshot.cancellationRequested;
  const canRetry = snapshot.status === "FAILED" || snapshot.status === "PARTIALLY_COMPLETED";
  const reportVersion = research.latestReportVersion;
  const longestDuration = Math.max(1, ...snapshot.steps.map((step) => step.durationMs ?? 0));

  return (
    <div className="space-y-6">
      <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5 sm:p-6">
        <div className="flex flex-col gap-5 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="rounded border border-emerald-300/20 bg-emerald-300/[0.06] px-2 py-1 text-xs font-bold text-emerald-100">{research.symbol ?? "—"}</span>
              <span className="rounded border border-[#294137] px-2 py-1 text-[10px] text-[#90a99d]">{statusLabels[snapshot.status]}</span>
              <span className="rounded border border-amber-300/20 px-2 py-1 text-[10px] text-amber-100">{snapshot.dataMode}</span>
            </div>
            <h1 className="mt-4 max-w-3xl text-2xl font-semibold leading-tight text-white">{research.title ?? research.query}</h1>
            <p className="mt-3 max-w-3xl text-sm leading-6 text-[#849b90]">{research.query}</p>
          </div>
          <div className="flex shrink-0 flex-wrap gap-2">
            {canCancel ? <button className="rounded-lg border border-rose-300/25 px-4 py-2 text-xs font-semibold text-rose-100 hover:bg-rose-300/[0.06] disabled:opacity-50" disabled={cancel.isPending} onClick={() => cancel.mutate()} type="button">{cancel.isPending ? "正在取消…" : "取消任务"}</button> : null}
            {canRetry ? <><select aria-label="重试起点" className="rounded-lg border border-[#294137] bg-[#09130f] px-3 py-2 text-xs text-[#dce8e2]" onChange={(event) => setRetryFromStep(event.target.value)} value={retryFromStep}><option value="">首个失败步骤</option>{snapshot.steps.filter((step) => step.status === "FAILED" || step.status === "SKIPPED").map((step) => <option key={step.step} value={step.step}>{stepLabel(step.step)}</option>)}</select><button className="rounded-lg bg-emerald-300 px-4 py-2 text-xs font-bold text-[#062219] disabled:opacity-50" disabled={retry.isPending} onClick={() => retry.mutate(retryFromStep)} type="button">{retry.isPending ? "正在重试…" : "重试任务"}</button></> : null}
          </div>
        </div>

        <div className="mt-7">
          <div className="mb-2 flex items-center justify-between text-xs">
            <span className="text-[#8da59a]">{snapshot.currentStep ? stepLabel(snapshot.currentStep) : statusLabels[snapshot.status]}</span>
            <span className="font-semibold text-emerald-100">{snapshot.progress}%</span>
          </div>
          <div aria-label={`研究进度 ${snapshot.progress}%`} aria-valuemax={100} aria-valuemin={0} aria-valuenow={snapshot.progress} className="h-2 overflow-hidden rounded-full bg-[#172820]" role="progressbar">
            <div className="h-full rounded-full bg-emerald-300 transition-[width]" style={{ width: `${snapshot.progress}%` }} />
          </div>
          <p className="mt-3 text-[11px] text-[#657b71]">已完成 {snapshot.completedSteps} / {snapshot.totalSteps} 个持久步骤；活动任务每 2 秒刷新。</p>
        </div>

        {snapshot.error ? <div className="mt-5 rounded-lg border border-rose-300/20 bg-rose-300/[0.05] p-4 text-xs text-rose-100" role="alert"><p className="font-semibold">{snapshot.error.code}</p><p className="mt-2 leading-5">{snapshot.error.message}</p></div> : null}
        {cancel.isError || retry.isError ? <p className="mt-4 text-xs text-rose-200" role="alert">{errorMessage(cancel.error ?? retry.error)}</p> : null}
        {cancel.isSuccess ? <p className="mt-4 text-xs text-emerald-200" role="status">取消请求已接受。</p> : null}
        {retry.isSuccess ? <p className="mt-4 text-xs text-emerald-200" role="status">重试任务已进入队列。</p> : null}
        {snapshot.status === "PARTIALLY_COMPLETED" ? <p className="mt-4 rounded-lg border border-amber-300/20 bg-amber-300/[0.05] p-4 text-xs text-amber-100" role="status">报告已安全发布，但部分可选模块不可用；请查看 warnings 与 Data Quality。</p> : null}
        {research.warnings.length > 0 ? <ul className="mt-4 space-y-2 rounded-lg border border-amber-300/15 p-4 text-[11px] text-amber-100/80">{research.warnings.map((warning) => <li key={`${warning.code}-${warning.message}`}><strong>{warning.code}</strong>：{warning.message}</li>)}</ul> : null}

        {reportVersion ? (
          <div className="mt-6 flex flex-col gap-3 rounded-lg border border-emerald-300/20 bg-emerald-300/[0.05] p-4 sm:flex-row sm:items-center sm:justify-between">
            <div><p className="text-sm font-semibold text-emerald-100">安全报告版本 {reportVersion} 已发布</p><p className="mt-1 text-xs text-[#8da59a]">报告绑定当前任务的不可变 Evidence 和 Mock snapshots。</p></div>
            <Link className="rounded-lg bg-emerald-300 px-4 py-2 text-center text-xs font-bold text-[#062219]" href={`/research/${researchId}/reports/${reportVersion}`}>打开研究报告</Link>
          </div>
        ) : null}
      </section>

      <section className="rounded-xl border border-[#20342b] bg-[#0c1713]">
        <div className="border-b border-[#1b2c25] px-5 py-4 sm:px-6"><h2 className="text-sm font-semibold text-white">执行步骤</h2></div>
        <ol className="divide-y divide-[#192a23]">
          {snapshot.steps.map((step, index) => (
            <li className="grid gap-3 px-5 py-4 sm:grid-cols-[36px_minmax(0,1fr)_120px] sm:items-center sm:px-6" key={step.step}>
              <span className="grid size-7 place-items-center rounded-full border border-[#294137] text-[10px] text-[#8da59a]">{index + 1}</span>
              <div><p className="text-xs font-semibold text-[#dce8e2]">{stepLabel(step.step)}</p><p className="mt-1 text-[11px] text-[#647b70]">尝试 {step.attemptCount}{step.durationMs !== null && step.durationMs !== undefined ? ` · ${step.durationMs} ms` : ""}</p>{step.durationMs ? <div className="mt-2 h-1.5 max-w-xs rounded bg-[#172820]"><div className="h-full rounded bg-emerald-300/60" style={{ width: `${Math.max(4, (step.durationMs / longestDuration) * 100)}%` }} /></div> : null}{step.error ? <p className="mt-2 text-[11px] text-rose-200">{step.error.code}: {step.error.message}</p> : null}</div>
              <span className="w-fit rounded border border-[#294137] px-2 py-1 text-[10px] text-[#8fa69b] sm:justify-self-end">{step.status}</span>
            </li>
          ))}
        </ol>
      </section>

      <p className="text-right text-[10px] text-[#53695f]">最后更新 {new Date(snapshot.updatedAt).toLocaleString("zh-CN")}</p>

      <p className="rounded-lg border border-amber-300/15 bg-amber-300/[0.04] px-4 py-3 text-[11px] leading-5 text-amber-100/70">{RESEARCH_DISCLAIMER}</p>
    </div>
  );
}
