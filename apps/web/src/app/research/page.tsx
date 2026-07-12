import { AppShell } from "@/components/app-shell";
import { ResearchHistory } from "@/components/research-history";

export default function ResearchHistoryPage() {
  return (
    <AppShell>
      <main className="mx-auto min-h-[70vh] max-w-[1320px] px-5 py-8 lg:px-8 lg:py-10">
        <div className="mb-7">
          <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-emerald-600">Research history</p>
          <h1 className="mt-3 text-3xl font-bold tracking-[-0.03em] text-slate-950">研究历史</h1>
          <p className="mt-3 text-sm leading-6 text-[#64748b]">重新打开持久化任务和不可变报告版本。</p>
        </div>
        <ResearchHistory />
      </main>
    </AppShell>
  );
}
