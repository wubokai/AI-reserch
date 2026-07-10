import type { ReactNode } from "react";

import { AppHeader } from "@/components/app-header";
import { DemoBanner } from "@/components/demo-banner";
import { RESEARCH_DISCLAIMER } from "@/lib/schemas";

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="app-grid min-h-screen bg-[#07100d]">
      <AppHeader />
      <DemoBanner />
      {children}
      <footer className="border-t border-[#172820] bg-[#07100d]">
        <div className="mx-auto flex max-w-[1440px] flex-col gap-2 px-5 py-6 text-[11px] leading-5 text-[#53695f] sm:flex-row sm:items-center sm:justify-between lg:px-8">
          <p>AI 量化研究助手 · Phase 3 Mock Closed Loop</p>
          <p>{RESEARCH_DISCLAIMER}</p>
        </div>
      </footer>
    </div>
  );
}
