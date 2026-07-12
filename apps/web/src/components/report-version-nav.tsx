"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";

import { fetchApi } from "@/lib/api-client";
import { reportVersionPageSchema } from "@/lib/schemas";

export function ReportVersionNav({ researchId, currentVersion }: { researchId: string; currentVersion: number }) {
  const versions = useQuery({
    queryKey: ["research", researchId, "report-versions"],
    queryFn: () => fetchApi(`/api/research/${researchId}/reports?size=100`, reportVersionPageSchema),
  });
  if (versions.isPending) return <p className="text-xs text-[#64748b]">正在读取报告版本…</p>;
  if (versions.isError) return <p className="text-xs text-rose-600" role="alert">报告版本暂不可用。</p>;
  return (
    <nav aria-label="报告版本" className="flex flex-wrap items-center gap-2">
      <span className="mr-1 text-[10px] uppercase tracking-[0.12em] text-[#64748b]">版本</span>
      {versions.data?.items.map((item) => <Link aria-current={item.version === currentVersion ? "page" : undefined} className={item.version === currentVersion ? "rounded-full bg-emerald-600 px-3 py-1.5 text-xs font-bold text-white shadow-sm" : "rounded-full bg-slate-100 px-3 py-1.5 text-xs text-slate-500 hover:bg-slate-200"} href={`/research/${researchId}/reports/${item.version}`} key={item.version}>v{item.version}</Link>)}
    </nav>
  );
}
