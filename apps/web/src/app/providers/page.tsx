import { AppShell } from "@/components/app-shell";
import { ProviderStatusPanel } from "@/components/provider-status-panel";

export default function ProvidersPage() {
  return (
    <AppShell>
      <main className="mx-auto min-h-[70vh] max-w-[1200px] px-5 py-8 lg:px-8 lg:py-10">
        <div className="mb-7"><p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-emerald-200/80">Provider operations</p><h1 className="mt-3 text-3xl font-semibold text-white">数据源状态</h1><p className="mt-3 text-sm text-[#849b90]">只读、脱敏的 Provider 配置、能力、健康和限流状态。</p></div>
        <ProviderStatusPanel />
      </main>
    </AppShell>
  );
}
