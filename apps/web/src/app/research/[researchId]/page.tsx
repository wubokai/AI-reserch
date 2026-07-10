import { AppShell } from "@/components/app-shell";
import { ResearchProgress } from "@/components/research-progress";

export default async function ResearchProgressPage({ params }: { params: Promise<{ researchId: string }> }) {
  const { researchId } = await params;
  return (
    <AppShell>
      <main className="mx-auto min-h-[70vh] max-w-[1200px] px-5 py-8 lg:px-8 lg:py-10">
        <ResearchProgress researchId={researchId} />
      </main>
    </AppShell>
  );
}
