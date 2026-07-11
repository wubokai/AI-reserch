"use client";

import { useQuery } from "@tanstack/react-query";

import { fetchApi } from "@/lib/api-client";
import { researchPageSchema } from "@/lib/schemas";

export function DashboardSummary() {
  const research = useQuery({
    queryKey: ["research", "dashboard-summary"],
    queryFn: () => fetchApi("/api/research?size=100&sort=createdAt,desc", researchPageSchema),
  });
  const items = research.data?.items ?? [];
  const completed = items.filter((item) => item.status === "COMPLETED").length;
  const partial = items.filter((item) => item.status === "PARTIALLY_COMPLETED").length;
  const active = items.filter((item) => !["COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED"].includes(item.status)).length;
  const reports = items.filter((item) => item.latestReportVersion !== null && item.latestReportVersion !== undefined).length;
  const cards = [
    ["研究任务", research.isPending ? "…" : String(research.data?.page.totalElements ?? 0)],
    ["执行中", research.isPending ? "…" : String(active)],
    ["已发布报告", research.isPending ? "…" : String(reports)],
    ["完成 / 部分", research.isPending ? "…" : `${completed} / ${partial}`],
  ] as const;

  return (
    <section aria-label="研究概览" className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
      {cards.map(([label, value]) => (
        <article className="rounded-xl border border-[#20342b] bg-[#0c1713] p-4" key={label}>
          <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-[#71887d]">{label}</p>
          <p className="mt-2 text-2xl font-semibold text-emerald-100">{value}</p>
        </article>
      ))}
      {research.isError ? <p className="col-span-full text-xs text-rose-200" role="alert">研究概览暂不可用。</p> : null}
    </section>
  );
}
