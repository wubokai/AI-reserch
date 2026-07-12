"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";

import { SearchIcon } from "@/components/icons";
import { fetchApi } from "@/lib/api-client";
import { researchPageSchema, researchStatusSchema } from "@/lib/schemas";

export function ResearchHistory() {
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [symbol, setSymbol] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({
    page: String(page),
    size: "20",
    sort: "createdAt,desc",
  });
  if (query.trim()) params.set("q", query.trim());
  if (status) params.set("status", status);
  if (symbol.trim()) params.set("symbol", symbol.trim().toUpperCase());
  if (from) params.set("from", new Date(`${from}T00:00:00Z`).toISOString());
  if (to) params.set("to", new Date(`${to}T23:59:59Z`).toISOString());

  const research = useQuery({
    queryKey: ["research", "history", page, query.trim(), status, symbol.trim(), from, to],
    queryFn: () => fetchApi(`/api/research?${params}`, researchPageSchema),
  });

  return (
    <section className="surface-card">
      <div className="soft-section m-3 grid gap-3 p-3 sm:grid-cols-2 lg:grid-cols-[minmax(0,1fr)_140px_190px_150px_150px]">
        <label className="relative">
          <span className="sr-only">搜索历史研究</span>
          <SearchIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-[#94a3b8]" />
          <input
            className="h-10 w-full rounded-lg border border-[#cbd5e1] bg-[#f8fafc] pl-10 pr-3 text-xs text-slate-950 placeholder:text-[#94a3b8]"
            onChange={(event) => {
              setPage(0);
              setQuery(event.target.value);
            }}
            placeholder="搜索证券或研究问题"
            value={query}
          />
        </label>
        <label><span className="sr-only">按证券筛选</span><input aria-label="按证券筛选" className="h-10 w-full rounded-lg border border-[#cbd5e1] bg-[#f8fafc] px-3 text-xs uppercase text-slate-950" maxLength={10} onChange={(event) => { setPage(0); setSymbol(event.target.value); }} placeholder="Ticker" value={symbol} /></label>
        <label>
          <span className="sr-only">按状态筛选</span>
          <select
            className="h-10 w-full rounded-lg border border-[#cbd5e1] bg-[#f8fafc] px-3 text-xs text-[#1f2937]"
            onChange={(event) => {
              setPage(0);
              setStatus(event.target.value);
            }}
            value={status}
          >
            <option value="">全部状态</option>
            {researchStatusSchema.options.map((value) => (
              <option key={value} value={value}>{value}</option>
            ))}
          </select>
        </label>
        <label><span className="sr-only">开始日期</span><input aria-label="开始日期" className="h-10 w-full rounded-lg border border-[#cbd5e1] bg-[#f8fafc] px-3 text-xs text-[#1f2937]" onChange={(event) => { setPage(0); setFrom(event.target.value); }} type="date" value={from} /></label>
        <label><span className="sr-only">结束日期</span><input aria-label="结束日期" className="h-10 w-full rounded-lg border border-[#cbd5e1] bg-[#f8fafc] px-3 text-xs text-[#1f2937]" onChange={(event) => { setPage(0); setTo(event.target.value); }} type="date" value={to} /></label>
      </div>

      {research.isPending ? <p className="p-6 text-xs text-[#64748b]">正在读取研究历史…</p> : null}
      {research.isError ? <p className="p-6 text-xs text-rose-600" role="alert">研究历史暂时不可用。</p> : null}
      {research.data?.items.length === 0 ? <p className="p-6 text-xs text-[#64748b]">没有符合条件的研究任务。</p> : null}
      <div className="space-y-1 px-2 pb-2">
        {research.data?.items.map((item) => (
          <article className="data-row grid gap-4 rounded-xl p-4 sm:grid-cols-[90px_minmax(0,1fr)_160px] sm:items-center" key={item.researchId}>
            <span className="w-fit rounded-lg bg-emerald-50 px-3 py-2 text-xs font-bold tracking-[0.08em] text-emerald-700">
              {item.symbol ?? "—"}
            </span>
            <div className="min-w-0">
              <p className="truncate text-sm font-medium text-[#1f2937]">{item.title ?? item.query}</p>
              <p className="mt-2 text-[11px] text-[#64748b]">
                {new Date(item.createdAt).toLocaleString("zh-CN")} · {item.progress}% · {item.dataMode}
              </p>
            </div>
            <div className="flex items-center gap-3 sm:justify-end">
              <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[10px] text-slate-500">{item.status}</span>
              <Link className="text-xs font-semibold text-emerald-600 hover:text-emerald-700" href={item.latestReportVersion ? `/research/${item.researchId}/reports/${item.latestReportVersion}` : `/research/${item.researchId}`}>
                {item.latestReportVersion ? "打开报告" : "查看进度"}
              </Link>
            </div>
          </article>
        ))}
      </div>

      {research.data && research.data.page.totalPages > 1 ? (
        <div className="flex items-center justify-between px-5 py-4 text-xs text-slate-500 sm:px-6">
          <button className="rounded-lg bg-slate-100 px-3 py-2 hover:bg-slate-200 disabled:opacity-40" disabled={research.data.page.first} onClick={() => setPage((value) => Math.max(0, value - 1))} type="button">上一页</button>
          <span>第 {research.data.page.number + 1} / {research.data.page.totalPages} 页</span>
          <button className="rounded-lg bg-slate-100 px-3 py-2 hover:bg-slate-200 disabled:opacity-40" disabled={research.data.page.last} onClick={() => setPage((value) => value + 1)} type="button">下一页</button>
        </div>
      ) : null}
    </section>
  );
}
