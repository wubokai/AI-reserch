import { ActivityIcon } from "@/components/icons";
import { AppShell } from "@/components/app-shell";
import { CoveragePanel } from "@/components/coverage-panel";
import { RecentResearch } from "@/components/recent-research";
import { ResearchForm } from "@/components/research-form";
import { WorkflowChart } from "@/components/workflow-chart";

const principles = [
  "金融数字仅来自数据源或确定性计算",
  "重大结论必须关联 Evidence",
  "事实、计算、推断与观点明确分类",
] as const;

export default function HomePage() {
  return (
    <AppShell>
      <main className="mx-auto max-w-[1440px] px-5 py-8 lg:px-8 lg:py-10">
        <section className="mb-8 flex flex-col gap-6 border-b border-[#1b2d25] pb-8 lg:flex-row lg:items-end lg:justify-between">
          <div className="max-w-3xl">
            <div className="mb-4 flex items-center gap-2 text-[11px] font-semibold uppercase tracking-[0.2em] text-emerald-200/80">
              <ActivityIcon className="size-4" />
              Research workspace
            </div>
            <h1 className="text-3xl font-semibold leading-tight tracking-[-0.03em] text-white sm:text-4xl">
              从数据与证据出发，
              <br className="hidden sm:block" />
              组织可审计的股票研究。
            </h1>
            <p className="mt-4 max-w-2xl text-sm leading-7 text-[#849b90]">
              这不是聊天机器人。平台将研究问题拆解为取数、计算、Evidence
              注册和报告验证步骤，并在数据不足时明确说明限制。
            </p>
          </div>

          <div className="grid gap-2 sm:grid-cols-3 lg:max-w-xl">
            {principles.map((principle, index) => (
              <div
                className="rounded-lg border border-[#21362d] bg-[#0b1612] px-3 py-3"
                key={principle}
              >
                <span className="text-[10px] font-semibold text-emerald-200/70">
                  0{index + 1}
                </span>
                <p className="mt-2 text-[11px] leading-5 text-[#91a69c]">
                  {principle}
                </p>
              </div>
            ))}
          </div>
        </section>

        <div className="grid gap-6 xl:grid-cols-[minmax(0,1.7fr)_minmax(320px,0.8fr)]">
          <ResearchForm />
          <div className="grid content-start gap-6">
            <WorkflowChart />
            <CoveragePanel />
          </div>
        </div>

        <div className="mt-6">
          <RecentResearch />
        </div>
      </main>

    </AppShell>
  );
}
