import { DatabaseIcon, ShieldIcon } from "@/components/icons";
import { coverageItems } from "@/lib/demo-data";

export function CoveragePanel() {
  return (
    <section className="rounded-xl border border-[#20342b] bg-[#0c1713]">
      <div className="flex items-center justify-between border-b border-[#1b2c25] px-5 py-4">
        <div>
          <p className="text-sm font-semibold text-white">数据与约束</p>
          <p className="mt-1 text-xs text-[#789085]">
            当前全部来自固定 Mock fixture。
          </p>
        </div>
        <DatabaseIcon className="size-4 text-emerald-200" />
      </div>
      <div className="divide-y divide-[#192a23]">
        {coverageItems.map((item) => (
          <div
            className="flex items-start justify-between gap-4 px-5 py-4"
            key={item.title}
          >
            <div>
              <p className="text-xs font-semibold text-[#d8e6df]">
                {item.title}
              </p>
              <p className="mt-1 text-[11px] leading-4 text-[#647c71]">
                {item.description}
              </p>
            </div>
            <span className="shrink-0 rounded border border-emerald-300/15 bg-emerald-300/[0.05] px-2 py-1 text-[10px] text-emerald-200">
              {item.state}
            </span>
          </div>
        ))}
      </div>
      <div className="flex items-start gap-3 border-t border-[#1b2c25] bg-[#09130f] px-5 py-4">
        <ShieldIcon className="mt-0.5 size-4 shrink-0 text-[#8eaa9d]" />
        <p className="text-[11px] leading-5 text-[#6e867b]">
          LLM 不得创建数字或 Evidence ID；数据不足时必须返回限制而不是补造内容。
        </p>
      </div>
    </section>
  );
}
