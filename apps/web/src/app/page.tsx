import { ActivityIcon } from "@/components/icons";
import { AppShell } from "@/components/app-shell";
import { CoveragePanel } from "@/components/coverage-panel";
import { DashboardSummary } from "@/components/dashboard-summary";
import { RecentResearch } from "@/components/recent-research";
import { ResearchForm } from "@/components/research-form";
import { WorkflowChart } from "@/components/workflow-chart";

const principles = [
  "所有数字均可追溯",
  "重要结论关联依据",
  "数据不足明确提示",
] as const;

export default function HomePage() {
  return (
    <AppShell>
      <main className="mx-auto max-w-[1540px] px-5 pb-12 pt-7 lg:px-8 lg:pt-9">
        <section className="mb-7 flex flex-col gap-7 lg:flex-row lg:items-end lg:justify-between">
          <div className="max-w-3xl">
            <div className="mb-3 flex items-center gap-2 text-[10px] font-semibold uppercase tracking-[0.18em] text-emerald-700">
              <ActivityIcon className="size-4" />
              US Equity Research Terminal
            </div>
            <h1 className="text-3xl font-bold leading-tight tracking-[-0.035em] text-slate-950 sm:text-[40px]">
              美股量化研究工作台
            </h1>
            <p className="mt-3 max-w-2xl text-sm leading-7 text-slate-600">
              输入证券代码，系统自动完成行情、财务、公告与宏观数据分析，先给出易懂结论，再提供每一项可核查依据。
            </p>
          </div>

          <div className="grid gap-2 sm:grid-cols-3 lg:max-w-[610px]">
            {principles.map((principle, index) => (
              <div className="soft-section min-h-[82px] px-4 py-3.5" key={principle}>
                <span className="text-[10px] font-bold text-emerald-600">
                  0{index + 1}
                </span>
                <p className="mt-1.5 text-[11px] leading-5 text-slate-600">
                  {principle}
                </p>
              </div>
            ))}
          </div>
        </section>

        <DashboardSummary />

        <div className="mb-4 mt-8 flex items-end justify-between gap-4" id="new-research">
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-[0.16em] text-emerald-700">
              New research
            </p>
            <h2 className="mt-1 text-xl font-bold tracking-tight text-slate-950">
              开始一项新研究
            </h2>
          </div>
          <p className="hidden text-xs text-slate-500 sm:block">
            免费数据源 · 自动保存 · 可随时返回查看
          </p>
        </div>

        <div className="grid gap-6 xl:grid-cols-[minmax(0,1.7fr)_minmax(320px,0.8fr)]">
          <ResearchForm />
          <div className="grid content-start gap-6">
            <WorkflowChart />
            <CoveragePanel />
          </div>
        </div>

        <div className="mt-7">
          <RecentResearch />
        </div>
      </main>
    </AppShell>
  );
}
