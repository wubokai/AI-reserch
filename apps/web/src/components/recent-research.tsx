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
    <section className="rounded-xl border border-[#20342b] bg-[#0c1713]">
      <div className="flex items-center justify-between border-b border-[#1b2c25] px-5 py-4 sm:px-6">
        <div>
          <p className="text-sm font-semibold text-white">最近研究</p>
          <p className="mt-1 text-xs text-[#789085]">当前演示用户的真实任务记录。</p>
        </div>
        <Link className="flex items-center gap-2 text-xs text-emerald-200 hover:text-emerald-100" href="/research">
          查看全部
          <ClockIcon className="size-4" />
        </Link>
      </div>

      {research.isPending ? <p className="px-5 py-6 text-xs text-[#789085]">正在读取历史任务…</p> : null}
      {research.isError ? <p className="px-5 py-6 text-xs text-rose-200">历史任务暂时不可用。</p> : null}
      {research.data?.items.length === 0 ? <p className="px-5 py-6 text-xs text-[#789085]">尚未创建研究任务。</p> : null}
      <div className="divide-y divide-[#192a23]">
        {research.data?.items.map((item) => (
          <Link
            className="grid gap-3 px-5 py-4 transition-colors hover:bg-white/[0.02] sm:grid-cols-[72px_minmax(0,1fr)_140px] sm:items-center sm:px-6"
            href={item.latestReportVersion ? `/research/${item.researchId}/reports/${item.latestReportVersion}` : `/research/${item.researchId}`}
            key={item.researchId}
          >
            <span className="w-fit rounded-md border border-[#294137] bg-[#101f19] px-2.5 py-1.5 text-xs font-bold tracking-[0.08em] text-emerald-100">
              {item.symbol ?? "—"}
            </span>
            <div className="min-w-0">
              <p className="truncate text-xs font-medium text-[#dce8e2]">{item.title ?? item.query}</p>
              <p className="mt-1 text-[11px] text-[#647b70]">进度 {item.progress}% · {item.dataMode}</p>
            </div>
            <span className="w-fit rounded border border-[#2a4037] px-2 py-1 text-[10px] text-[#8fa69b] sm:justify-self-end">
              {item.status}
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}
