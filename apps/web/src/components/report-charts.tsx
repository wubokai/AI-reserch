"use client";

import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import type { CanonicalResearchReport } from "@/lib/schemas";

const scenarioColors = ["#10b981", "#4f7cff", "#f05252"] as const;

export function ReportCharts({ report }: { report: CanonicalResearchReport }) {
  const scenarioLabels = { BULL: "乐观", BASE: "基准", BEAR: "谨慎" } as const;
  const scenarioData = report.scenarioAnalysis.scenarios.map((scenario) => ({
    name: scenarioLabels[scenario.name],
    impliedPrice: Number(scenario.impliedPrice),
    probability: Number(scenario.probability) * 100,
  }));

  return (
    <section
      aria-label="报告图表"
      className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_240px]"
    >
      <div className="surface-card surface-card-interactive p-5">
        <h2 className="text-sm font-semibold text-slate-950">
          不同情况下可能的价格
        </h2>
        <p className="mt-1 text-[11px] text-slate-500">
          虚线为当前市场价格，仅用于理解范围，不是目标价。
        </p>
        <div className="mt-4 h-64 min-w-0">
          <ResponsiveContainer height="100%" width="100%">
            <BarChart data={scenarioData}>
              <CartesianGrid stroke="#edf1f4" vertical={false} />
              <XAxis
                axisLine={false}
                dataKey="name"
                stroke="#64748b"
                tickLine={false}
              />
              <YAxis
                axisLine={false}
                stroke="#94a3b8"
                tickLine={false}
                width={48}
              />
              <Tooltip
                contentStyle={{
                  background: "#ffffff",
                  border: "0",
                  borderRadius: "12px",
                  boxShadow: "0 12px 28px rgba(15, 23, 42, 0.1)",
                  fontSize: "12px",
                }}
              />
              {report.scenarioAnalysis.currentPrice !== undefined ? (
                <ReferenceLine
                  label={{ fill: "#b45309", fontSize: 10, value: "当前价" }}
                  stroke="#f59e0b"
                  strokeDasharray="5 4"
                  y={Number(report.scenarioAnalysis.currentPrice)}
                />
              ) : null}
              <Bar
                animationDuration={700}
                dataKey="impliedPrice"
                name="情景价格"
                radius={[7, 7, 0, 0]}
              >
                {scenarioData.map((item, index) => (
                  <Cell
                    fill={scenarioColors[index] ?? "#4f7cff"}
                    key={item.name}
                  />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="surface-card surface-card-interactive p-5">
        <h2 className="text-sm font-semibold text-slate-950">数据可靠度</h2>
        <div
          className="mx-auto mt-6 grid size-36 place-items-center rounded-full transition-transform duration-300 hover:scale-[1.03]"
          style={{
            background: `conic-gradient(#10b981 ${report.dataQuality.score * 100}%, #edf1f4 0)`,
          }}
        >
          <div className="grid size-28 place-items-center rounded-full bg-white shadow-inner">
            <span className="text-2xl font-bold text-emerald-700">
              {Math.round(report.dataQuality.score * 100)}%
            </span>
          </div>
        </div>
        <p className="mt-5 text-center text-[11px] leading-5 text-slate-500">
          综合完整性、更新时间和来源一致性
        </p>
      </div>
    </section>
  );
}
