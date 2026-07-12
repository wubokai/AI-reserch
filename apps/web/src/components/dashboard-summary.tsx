"use client";

import { useQuery } from "@tanstack/react-query";

import { fetchApi } from "@/lib/api-client";
import { researchPageSchema } from "@/lib/schemas";

export function DashboardSummary() {
  const research = useQuery({
    queryKey: ["research", "dashboard-summary"],
    queryFn: () =>
      fetchApi(
        "/api/research?size=100&sort=createdAt,desc",
        researchPageSchema,
      ),
  });
  const items = research.data?.items ?? [];
  const completed = items.filter((item) => item.status === "COMPLETED").length;
  const partial = items.filter(
    (item) => item.status === "PARTIALLY_COMPLETED",
  ).length;
  const active = items.filter(
    (item) =>
      !["COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED"].includes(
        item.status,
      ),
  ).length;
  const reports = items.filter(
    (item) =>
      item.latestReportVersion !== null &&
      item.latestReportVersion !== undefined,
  ).length;
  const cards = [
    [
      "研究任务",
      research.isPending
        ? "…"
        : String(research.data?.page.totalElements ?? 0),
      "累计创建",
    ],
    [
      "执行中",
      research.isPending ? "…" : String(active),
      active > 0 ? "实时更新" : "当前空闲",
    ],
    [
      "已发布报告",
      research.isPending ? "…" : String(reports),
      "可随时查看",
    ],
    [
      "完成 / 部分",
      research.isPending ? "…" : `${completed} / ${partial}`,
      "可靠性状态",
    ],
  ] as const;

  return (
    <section
      aria-label="研究概览"
      className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4"
    >
      {cards.map(([label, value, note]) => (
        <article
          className="metric-card surface-card surface-card-interactive min-h-[112px] p-5"
          key={label}
        >
          <div className="relative z-10 flex items-center justify-between gap-3">
            <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate-500">
              {label}
            </p>
            <span className="size-1.5 rounded-full bg-emerald-500 shadow-[0_0_0_4px_rgba(16,185,129,0.08)]" />
          </div>
          <p className="relative z-10 mt-2 text-2xl font-bold tracking-tight text-slate-950">
            {value}
          </p>
          <p className="relative z-10 mt-1 text-[10px] text-slate-400">
            {note}
          </p>
        </article>
      ))}
      {research.isError ? (
        <p className="col-span-full text-xs text-rose-600" role="alert">
          研究概览暂不可用。
        </p>
      ) : null}
    </section>
  );
}
