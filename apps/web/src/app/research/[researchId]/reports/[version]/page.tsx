import { notFound } from "next/navigation";

import { AppShell } from "@/components/app-shell";
import { ResearchReport } from "@/components/research-report";

export default async function ResearchReportPage({ params }: { params: Promise<{ researchId: string; version: string }> }) {
  const { researchId, version: rawVersion } = await params;
  const version = Number(rawVersion);
  if (!Number.isSafeInteger(version) || version < 1) notFound();
  return (
    <AppShell>
      <main className="mx-auto min-h-[70vh] max-w-[1440px] px-5 py-8 lg:px-8 lg:py-10">
        <ResearchReport researchId={researchId} version={version} />
      </main>
    </AppShell>
  );
}
