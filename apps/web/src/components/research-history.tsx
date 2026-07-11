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
    <section className="rounded-xl border border-[#20342b] bg-[#0c1713]">
      <div className="grid gap-4 border-b border-[#1b2c25] p-5 sm:grid-cols-2 lg:grid-cols-[minmax(0,1fr)_140px_190px_150px_150px] sm:p-6">
        <label className="relative">
          <span className="sr-only">搜索历史研究</span>
          <SearchIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-[#60786d]" />
          <input
            className="h-10 w-full rounded-lg border border-[#294137] bg-[#09120f] pl-10 pr-3 text-xs text-white placeholder:text-[#52685e]"
            onChange={(event) => {
              setPage(0);
              setQuery(event.target.value);
            }}
            placeholder="搜索证券或研究问题"
            value={query}
          />
        </label>
        <label><span className="sr-only">按证券筛选</span><input aria-label="按证券筛选" className="h-10 w-full rounded-lg border border-[#294137] bg-[#09120f] px-3 text-xs uppercase text-white" maxLength={10} onChange={(event) => { setPage(0); setSymbol(event.target.value); }} placeholder="Ticker" value={symbol} /></label>
        <label>
          <span className="sr-only">按状态筛选</span>
          <select
            className="h-10 w-full rounded-lg border border-[#294137] bg-[#09120f] px-3 text-xs text-[#d8e5de]"
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
        <label><span className="sr-only">开始日期</span><input aria-label="开始日期" className="h-10 w-full rounded-lg border border-[#294137] bg-[#09120f] px-3 text-xs text-[#d8e5de]" onChange={(event) => { setPage(0); setFrom(event.target.value); }} type="date" value={from} /></label>
        <label><span className="sr-only">结束日期</span><input aria-label="结束日期" className="h-10 w-full rounded-lg border border-[#294137] bg-[#09120f] px-3 text-xs text-[#d8e5de]" onChange={(event) => { setPage(0); setTo(event.target.value); }} type="date" value={to} /></label>
      </div>

      {research.isPending ? <p className="p-6 text-xs text-[#789085]">正在读取研究历史…</p> : null}
      {research.isError ? <p className="p-6 text-xs text-rose-200" role="alert">研究历史暂时不可用。</p> : null}
      {research.data?.items.length === 0 ? <p className="p-6 text-xs text-[#789085]">没有符合条件的研究任务。</p> : null}
      <div className="divide-y divide-[#192a23]">
        {research.data?.items.map((item) => (
          <article className="grid gap-4 p-5 sm:grid-cols-[90px_minmax(0,1fr)_160px] sm:items-center sm:p-6" key={item.researchId}>
            <span className="w-fit rounded-md border border-[#294137] bg-[#101f19] px-3 py-2 text-xs font-bold tracking-[0.08em] text-emerald-100">
              {item.symbol ?? "—"}
            </span>
            <div className="min-w-0">
              <p className="truncate text-sm font-medium text-[#dce8e2]">{item.title ?? item.query}</p>
              <p className="mt-2 text-[11px] text-[#647b70]">
                {new Date(item.createdAt).toLocaleString("zh-CN")} · {item.progress}% · {item.dataMode}
              </p>
            </div>
            <div className="flex items-center gap-3 sm:justify-end">
              <span className="rounded border border-[#2a4037] px-2 py-1 text-[10px] text-[#8fa69b]">{item.status}</span>
              <Link className="text-xs font-semibold text-emerald-200 hover:text-emerald-100" href={item.latestReportVersion ? `/research/${item.researchId}/reports/${item.latestReportVersion}` : `/research/${item.researchId}`}>
                {item.latestReportVersion ? "打开报告" : "查看进度"}
              </Link>
            </div>
          </article>
        ))}
      </div>

      {research.data && research.data.page.totalPages > 1 ? (
        <div className="flex items-center justify-between border-t border-[#1b2c25] px-5 py-4 text-xs text-[#789085] sm:px-6">
          <button className="rounded border border-[#294137] px-3 py-2 disabled:opacity-40" disabled={research.data.page.first} onClick={() => setPage((value) => Math.max(0, value - 1))} type="button">上一页</button>
          <span>第 {research.data.page.number + 1} / {research.data.page.totalPages} 页</span>
          <button className="rounded border border-[#294137] px-3 py-2 disabled:opacity-40" disabled={research.data.page.last} onClick={() => setPage((value) => value + 1)} type="button">下一页</button>
        </div>
      ) : null}
    </section>
  );
}
