"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { LogoMark } from "@/components/icons";
import { SystemHealth } from "@/components/system-health";

const navItems = [
  { href: "/", label: "研究台" },
  { href: "/research", label: "历史报告" },
  { href: "/providers", label: "数据源" },
] as const;

export function AppHeader() {
  const pathname = usePathname();

  return (
    <header className="sticky top-0 z-40 border-b border-slate-200/80 bg-white/95 shadow-[0_1px_0_rgba(15,23,42,0.02)] backdrop-blur-xl">
      <div className="border-b border-slate-100 bg-slate-50/80">
        <div className="mx-auto flex h-8 max-w-[1540px] items-center justify-between px-5 text-[10px] text-slate-500 lg:px-8">
          <p className="truncate tracking-wide">
            美股 · ETF · SEC 公告 · 宏观数据 · 可审计量化研究
          </p>
          <SystemHealth />
        </div>
      </div>

      <div className="mx-auto flex min-h-16 max-w-[1540px] items-center justify-between gap-5 px-5 lg:px-8">
        <div className="flex min-w-0 items-center gap-3">
          <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-emerald-600 text-white shadow-[0_7px_18px_rgba(5,150,105,0.18)]">
            <LogoMark className="size-5" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-bold tracking-tight text-slate-950">
              AI Quant Research
            </p>
            <p className="truncate text-[10px] text-slate-500">量化研究工作台</p>
          </div>
        </div>

        <nav aria-label="主导航" className="hidden h-16 items-center gap-7 md:flex">
          {navItems.map((item) => {
            const active =
              item.href === "/"
                ? pathname === "/"
                : pathname.startsWith(item.href);
            return (
              <Link
                aria-current={active ? "page" : undefined}
                href={item.href}
                key={item.href}
                className={
                  active
                    ? "relative flex h-full items-center text-xs font-semibold text-slate-950 after:absolute after:inset-x-0 after:bottom-0 after:h-0.5 after:rounded-full after:bg-emerald-600"
                    : "flex h-full items-center text-xs font-medium text-slate-500 hover:text-slate-950"
                }
              >
                {item.label}
              </Link>
            );
          })}
        </nav>

        <div className="flex items-center gap-3">
          <Link
            className="hidden rounded-lg bg-slate-900 px-3.5 py-2 text-[11px] font-semibold text-white shadow-sm hover:-translate-y-0.5 hover:bg-emerald-700 sm:inline-flex"
            href="/#new-research"
          >
            新建研究
          </Link>
          <div
            aria-label="当前用户"
            className="grid size-8 place-items-center rounded-full bg-slate-100 text-xs font-semibold text-slate-600 ring-1 ring-inset ring-slate-200"
          >
            D
          </div>
        </div>
      </div>

      <nav
        aria-label="移动导航"
        className="mx-auto flex max-w-[1540px] overflow-x-auto border-t border-slate-100 px-5 py-2 md:hidden"
      >
        {navItems.map((item) => (
          <Link
            className="mr-2 shrink-0 rounded-full bg-slate-50 px-3 py-2 text-xs text-slate-600"
            href={item.href}
            key={item.href}
          >
            {item.label}
          </Link>
        ))}
      </nav>
    </header>
  );
}
