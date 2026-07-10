import { LogoMark } from "@/components/icons";
import { SystemHealth } from "@/components/system-health";

const navItems = ["研究台", "历史报告", "数据源"];

export function AppHeader() {
  return (
    <header className="border-b border-[#1d3028] bg-[#08120f]/95">
      <div className="mx-auto flex min-h-16 max-w-[1440px] items-center justify-between gap-5 px-5 lg:px-8">
        <div className="flex min-w-0 items-center gap-3">
          <span className="grid size-9 shrink-0 place-items-center rounded-lg border border-emerald-300/30 bg-emerald-300/10 text-emerald-200">
            <LogoMark className="size-5" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold tracking-wide text-white">
              AI Quant Research
            </p>
            <p className="truncate text-[11px] text-[#7f978c]">
              Evidence-first workspace
            </p>
          </div>
        </div>

        <nav
          aria-label="主导航"
          className="hidden items-center gap-1 rounded-lg border border-[#1d3028] bg-[#0b1712] p-1 md:flex"
        >
          {navItems.map((item, index) => (
            <button
              key={item}
              className={
                index === 0
                  ? "rounded-md bg-[#173529] px-4 py-2 text-xs font-medium text-emerald-100"
                  : "rounded-md px-4 py-2 text-xs font-medium text-[#829b90] transition-colors hover:bg-white/5 hover:text-white"
              }
              type="button"
            >
              {item}
            </button>
          ))}
        </nav>

        <div className="flex items-center gap-3">
          <SystemHealth />
          <div
            aria-label="演示用户"
            className="grid size-8 place-items-center rounded-full border border-[#294137] bg-[#14251e] text-xs font-semibold text-[#c8d8d0]"
          >
            D
          </div>
        </div>
      </div>
    </header>
  );
}
