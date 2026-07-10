import { ShieldIcon } from "@/components/icons";

export function DemoBanner() {
  return (
    <aside
      aria-label="演示数据提示"
      className="flex flex-col gap-3 border-b border-amber-300/20 bg-amber-300/[0.055] px-5 py-3 text-amber-100 sm:flex-row sm:items-center sm:justify-between lg:px-8"
    >
      <div className="flex items-start gap-3">
        <ShieldIcon className="mt-0.5 size-4 shrink-0 text-amber-200" />
        <div>
          <p className="text-xs font-semibold tracking-[0.08em]">
            DEMO DATA — NOT REAL MARKET DATA
          </p>
          <p className="mt-1 text-xs leading-5 text-amber-100/65">
            当前为 Phase 1 界面骨架，仅展示固定 Mock 工作流，不包含实时行情或投资结论。
          </p>
        </div>
      </div>
      <span className="w-fit rounded border border-amber-200/20 px-2 py-1 text-[10px] font-semibold uppercase tracking-[0.16em] text-amber-100/80">
        dataMode: MOCK
      </span>
    </aside>
  );
}
