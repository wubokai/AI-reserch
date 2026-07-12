import { DatabaseIcon, ShieldIcon } from "@/components/icons";
import { coverageItems } from "@/lib/demo-data";

export function CoveragePanel() {
  return (
    <section className="surface-card">
      <div className="flex items-center justify-between px-5 pb-3 pt-5">
        <div>
          <p className="text-sm font-semibold text-slate-950">数据与约束</p>
          <p className="mt-1 text-xs text-slate-500">
            数据来源、计算规则与安全边界。
          </p>
        </div>
        <DatabaseIcon className="size-4 text-emerald-600" />
      </div>
      <div className="space-y-1 px-2 pb-2">
        {coverageItems.map((item) => (
          <div
            className="data-row flex items-start justify-between gap-4 rounded-xl px-3 py-3"
            key={item.title}
          >
            <div>
              <p className="text-xs font-semibold text-[#1f2937]">
                {item.title}
              </p>
              <p className="mt-1 text-[11px] leading-4 text-[#64748b]">
                {item.description}
              </p>
            </div>
            <span className="shrink-0 rounded-full bg-emerald-50 px-2.5 py-1 text-[10px] font-medium text-emerald-700">
              {item.state}
            </span>
          </div>
        ))}
      </div>
      <div className="soft-section mx-3 mb-3 flex items-start gap-3 px-4 py-3.5">
        <ShieldIcon className="mt-0.5 size-4 shrink-0 text-[#64748b]" />
        <p className="text-[11px] leading-5 text-[#64748b]">
          LLM 不得创建数字或 Evidence ID；数据不足时必须返回限制而不是补造内容。
        </p>
      </div>
    </section>
  );
}
