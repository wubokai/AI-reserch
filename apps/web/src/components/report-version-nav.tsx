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
  if (versions.isPending) return <p className="text-xs text-[#71887d]">正在读取报告版本…</p>;
  if (versions.isError) return <p className="text-xs text-rose-200" role="alert">报告版本暂不可用。</p>;
  return (
    <nav aria-label="报告版本" className="flex flex-wrap items-center gap-2">
      <span className="mr-1 text-[10px] uppercase tracking-[0.12em] text-[#647b70]">版本</span>
      {versions.data?.items.map((item) => <Link aria-current={item.version === currentVersion ? "page" : undefined} className={item.version === currentVersion ? "rounded bg-emerald-300 px-3 py-1.5 text-xs font-bold text-[#062219]" : "rounded border border-[#294137] px-3 py-1.5 text-xs text-[#9aafa5]"} href={`/research/${researchId}/reports/${item.version}`} key={item.version}>v{item.version}</Link>)}
    </nav>
  );
}
