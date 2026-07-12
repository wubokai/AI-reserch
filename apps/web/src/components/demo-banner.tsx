import { ShieldIcon } from "@/components/icons";

export function DemoBanner({ dataMode = "MOCK" }: { dataMode?: "REAL" | "MOCK" }) {
  if (dataMode === "REAL") {
    return null;
  }

  return (
    <aside
      aria-label="演示数据提示"
      className="flex flex-col gap-3 border-b border-amber-200 bg-amber-50 px-5 py-3 text-amber-800 sm:flex-row sm:items-center sm:justify-between lg:px-8"
    >
      <div className="flex items-start gap-3">
        <ShieldIcon className="mt-0.5 size-4 shrink-0 text-amber-700" />
        <div>
          <p className="text-xs font-semibold tracking-[0.08em]">
            DEMO DATA — NOT REAL MARKET DATA
          </p>
          <p className="mt-1 text-xs leading-5 text-amber-700">
            当前闭环使用固定 Mock fixture，不包含实时行情；所有结论只代表演示数据中的可审计结果。
          </p>
        </div>
      </div>
      <span className="w-fit rounded-full bg-amber-100 px-2.5 py-1 text-[10px] font-semibold uppercase tracking-[0.16em] text-amber-700">
        dataMode: MOCK
      </span>
    </aside>
  );
}
