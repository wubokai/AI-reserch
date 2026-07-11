"use client";

import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

import type { CanonicalResearchReport } from "@/lib/schemas";

export function ReportCharts({ report }: { report: CanonicalResearchReport }) {
  const scenarioLabels = { BULL: "乐观", BASE: "基准", BEAR: "谨慎" } as const;
  const scenarioData = report.scenarioAnalysis.scenarios.map((scenario) => ({
    name: scenarioLabels[scenario.name],
    impliedPrice: Number(scenario.impliedPrice),
    probability: Number(scenario.probability) * 100,
  }));
  return (
    <section aria-label="报告图表" className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_240px]">
      <div className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5"><h2 className="text-sm font-semibold text-white">不同情况下可能的价格</h2><p className="mt-1 text-[11px] text-[#647b70]">对比乐观、基准和谨慎三种情况，仅用于理解范围，不是目标价。</p><div className="mt-4 h-64 min-w-0"><ResponsiveContainer height="100%" width="100%"><BarChart data={scenarioData}><CartesianGrid stroke="#1d3028" vertical={false} /><XAxis dataKey="name" stroke="#789085" tickLine={false} /><YAxis stroke="#789085" tickLine={false} width={42} /><Tooltip contentStyle={{ background: "#09130f", border: "1px solid #294137" }} /><Bar dataKey="impliedPrice" fill="#6ee7b7" name="情景价格" radius={[5, 5, 0, 0]} /></BarChart></ResponsiveContainer></div></div>
      <div className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5"><h2 className="text-sm font-semibold text-white">数据可靠度</h2><div className="mx-auto mt-6 grid size-36 place-items-center rounded-full" style={{ background: `conic-gradient(#6ee7b7 ${report.dataQuality.score * 100}%, #172820 0)` }}><div className="grid size-28 place-items-center rounded-full bg-[#0c1713]"><span className="text-2xl font-semibold text-emerald-100">{Math.round(report.dataQuality.score * 100)}%</span></div></div><p className="mt-5 text-center text-[11px] text-[#71887d]">综合数据完整性、更新时间和来源一致性</p></div>
    </section>
  );
}
