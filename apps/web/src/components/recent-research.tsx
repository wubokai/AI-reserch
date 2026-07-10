import { ClockIcon } from "@/components/icons";
import { recentDemoResearch } from "@/lib/demo-data";

export function RecentResearch() {
  return (
    <section className="rounded-xl border border-[#20342b] bg-[#0c1713]">
      <div className="flex items-center justify-between border-b border-[#1b2c25] px-5 py-4 sm:px-6">
        <div>
          <p className="text-sm font-semibold text-white">最近研究</p>
          <p className="mt-1 text-xs text-[#789085]">
            固定演示记录，不代表真实历史报告。
          </p>
        </div>
        <ClockIcon className="size-4 text-[#7f998d]" />
      </div>

      <div className="divide-y divide-[#192a23]">
        {recentDemoResearch.map((research) => (
          <article
            className="grid gap-3 px-5 py-4 sm:grid-cols-[72px_minmax(0,1fr)_120px] sm:items-center sm:px-6"
            key={research.symbol}
          >
            <span className="w-fit rounded-md border border-[#294137] bg-[#101f19] px-2.5 py-1.5 text-xs font-bold tracking-[0.08em] text-emerald-100">
              {research.symbol}
            </span>
            <div className="min-w-0">
              <p className="truncate text-xs font-medium text-[#dce8e2]">
                {research.question}
              </p>
              <p className="mt-1 text-[11px] text-[#647b70]">
                {research.step}
              </p>
            </div>
            <span className="w-fit rounded border border-[#2a4037] px-2 py-1 text-[10px] text-[#8fa69b] sm:justify-self-end">
              {research.status}
            </span>
          </article>
        ))}
      </div>
    </section>
  );
}
