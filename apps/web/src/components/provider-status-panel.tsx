"use client";

import { useQuery } from "@tanstack/react-query";

import { errorMessage, fetchApi } from "@/lib/api-client";
import { providerStatusResponseSchema } from "@/lib/schemas";

function statusClass(status: string) {
  if (status === "UP") return "bg-emerald-50 text-emerald-700";
  if (status === "DOWN") return "bg-rose-50 text-rose-700";
  return "bg-amber-50 text-amber-800";
}

export function ProviderStatusPanel() {
  const query = useQuery({
    queryKey: ["providers", "status"],
    queryFn: () => fetchApi("/api/providers/status", providerStatusResponseSchema),
    refetchInterval: 30_000,
  });

  if (query.isPending) {
    return <div className="surface-card p-6 text-sm text-[#64748b]">正在读取 Provider 状态…</div>;
  }
  if (query.isError || !query.data) {
    return <div className="rounded-xl border border-rose-200 bg-rose-50 p-6 text-sm text-rose-700" role="alert">{errorMessage(query.error)}</div>;
  }

  return (
    <div className="space-y-5">
      <section className="flex flex-col gap-3 surface-card p-5 sm:flex-row sm:items-center sm:justify-between">
        <div><p className="text-xs text-[#64748b]">运行模式</p><p className="mt-1 text-lg font-semibold text-slate-950">{query.data.dataMode}</p></div>
        <div className="text-left sm:text-right"><p className="text-xs text-[#64748b]">整体状态</p><p className="mt-1 text-lg font-semibold text-emerald-700">{query.data.status}</p></div>
        <p className="text-[11px] text-[#64748b]">检查时间 {new Date(query.data.checkedAt).toLocaleString("zh-CN")}</p>
      </section>
      {query.data.providers.length === 0 ? <p className="rounded-xl border border-amber-200 bg-amber-50 p-5 text-sm text-amber-800">当前模式未公开可用 Provider；系统不会展示或推断密钥。</p> : null}
      <section className="grid gap-4 lg:grid-cols-2">
        {query.data.providers.map((provider) => (
          <article className="surface-card surface-card-interactive p-5" key={provider.name}>
            <div className="flex items-start justify-between gap-4">
              <div><h2 className="text-sm font-semibold text-slate-950">{provider.name}</h2><p className="mt-1 text-[11px] text-[#64748b]">{provider.mode} · {provider.configured ? "已配置" : "未配置"}</p></div>
              <span className={`rounded-full px-2.5 py-1 text-[10px] font-semibold ${statusClass(provider.status)}`}>{provider.status}</span>
            </div>
            <div className="mt-4 flex flex-wrap gap-2">{provider.capabilities.map((capability) => <span className="rounded bg-[#f0fdfa] px-2 py-1 text-[10px] text-[#64748b]" key={capability}>{capability}</span>)}</div>
            <dl className="mt-5 grid grid-cols-2 gap-3 text-[11px]"><dt className="text-[#64748b]">延迟</dt><dd className="text-right text-[#1f2937]">{provider.latencyMs === null ? "—" : `${provider.latencyMs} ms`}</dd><dt className="text-[#64748b]">Rate limit</dt><dd className="text-right text-[#1f2937]">{provider.rateLimit.limited ? `${provider.rateLimit.remaining ?? "—"} 剩余` : "无限制/未报告"}</dd><dt className="text-[#64748b]">最后成功</dt><dd className="text-right text-[#1f2937]">{provider.lastSuccessAt ? new Date(provider.lastSuccessAt).toLocaleString("zh-CN") : "—"}</dd></dl>
            {provider.message ? <p className="mt-4 border-t border-[#e7ebef] pt-4 text-[11px] leading-5 text-[#64748b]">{provider.message}</p> : null}
          </article>
        ))}
      </section>
    </div>
  );
}
