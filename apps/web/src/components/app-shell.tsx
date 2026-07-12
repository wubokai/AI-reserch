import type { ReactNode } from "react";

import { AppHeader } from "@/components/app-header";
import { DemoBanner } from "@/components/demo-banner";
import { RESEARCH_DISCLAIMER } from "@/lib/schemas";

export function AppShell({ children }: { children: ReactNode }) {
  const runtimeDataMode = process.env.DATA_MODE === "REAL" ? "REAL" : "MOCK";

  return (
    <div className="app-grid min-h-screen bg-[#f5f7f8] text-slate-900">
      <AppHeader />
      <DemoBanner dataMode={runtimeDataMode} />
      {children}
      <footer className="mt-10 bg-white/80">
        <div className="mx-auto flex max-w-[1540px] flex-col gap-2 px-5 py-7 text-[11px] leading-5 text-slate-400 sm:flex-row sm:items-center sm:justify-between lg:px-8">
          <p>AI Quant Research · Evidence-first equity research</p>
          <p>{RESEARCH_DISCLAIMER}</p>
        </div>
      </footer>
    </div>
  );
}
