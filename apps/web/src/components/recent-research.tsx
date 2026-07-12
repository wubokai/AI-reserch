"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";

import { ClockIcon } from "@/components/icons";
import { fetchApi } from "@/lib/api-client";
import { researchPageSchema } from "@/lib/schemas";

export function RecentResearch() {
  const research = useQuery({
    queryKey: ["research", "recent"],
    queryFn: () =>
      fetchApi(
        "/api/research?size=5&sort=createdAt,desc",
        researchPageSchema,
      ),
  });

  return (
    <section className="surface-card">
      <div className="flex items-center justify-between px-5 pb-3 pt-5 sm:px-6">
        <div>
          <p className="text-sm font-semibold text-slate-950">最近研究</p>
          <p className="mt-1 text-xs text-slate-500">最近创建和更新的研究任务。</p>
        </div>
        <Link className="flex items-center gap-2 text-xs text-emerald-600 hover:text-emerald-700" href="/research">
          查看全部
          <ClockIcon className="size-4" />
        </Link>
      </div>

      {research.isPending ? <p className="px-5 py-6 text-xs text-[#64748b]">正在读取历史任务…</p> : null}
      {research.isError ? <p className="px-5 py-6 text-xs text-rose-600">历史任务暂时不可用。</p> : null}
      {research.data?.items.length === 0 ? <p className="px-5 py-6 text-xs text-[#64748b]">尚未创建研究任务。</p> : null}
      <div className="space-y-1 px-2 pb-2">
        {research.data?.items.map((item) => (
          <Link
            className="data-row grid gap-3 rounded-xl px-3 py-3 sm:grid-cols-[72px_minmax(0,1fr)_140px] sm:items-center sm:px-4"
            href={item.latestReportVersion ? `/research/${item.researchId}/reports/${item.latestReportVersion}` : `/research/${item.researchId}`}
            key={item.researchId}
          >
            <span className="w-fit rounded-lg bg-emerald-50 px-2.5 py-1.5 text-xs font-bold tracking-[0.08em] text-emerald-700">
              {item.symbol ?? "—"}
            </span>
            <div className="min-w-0">
              <p className="truncate text-xs font-medium text-[#1f2937]">{item.title ?? item.query}</p>
              <p className="mt-1 text-[11px] text-[#64748b]">进度 {item.progress}% · {item.dataMode}</p>
            </div>
            <span className="w-fit rounded-full bg-slate-100 px-2.5 py-1 text-[10px] text-slate-500 sm:justify-self-end">
              {item.status}
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}
