"use client";

import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import { workflowData } from "@/lib/demo-data";

export function WorkflowChart() {
  return (
    <section className="surface-card p-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-semibold text-slate-950">研究执行链路</p>
          <p className="mt-1 text-xs text-slate-500">
            从原始数据到可验证报告的完整流程。
          </p>
        </div>
        <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[10px] font-semibold text-slate-500">
          LIVE FLOW
        </span>
      </div>

      <div
        aria-label="研究工作流完成度示意图"
        className="mt-5 h-48 w-full"
      >
        <ResponsiveContainer height="100%" width="100%">
          <LineChart
            data={workflowData}
            margin={{ bottom: 0, left: 0, right: 8, top: 8 }}
          >
            <CartesianGrid stroke="#edf1f4" strokeDasharray="3 3" />
            <XAxis
              axisLine={false}
              dataKey="stage"
              fontSize={10}
              stroke="#64748b"
              tickLine={false}
            />
            <YAxis
              axisLine={false}
              domain={[0, 100]}
              fontSize={10}
              stroke="#94a3b8"
              tickLine={false}
              width={32}
            />
            <Tooltip
              contentStyle={{
                background: "#ffffff",
                border: "0",
                borderRadius: "12px",
                boxShadow: "0 12px 28px rgba(15, 23, 42, 0.1)",
                color: "#111827",
                fontSize: "12px",
              }}
              cursor={{ stroke: "#cbd5e1" }}
              formatter={(value) => [String(value) + "%", "流程进度"]}
            />
            <Line
              activeDot={{ fill: "#047857", r: 5, stroke: "#ffffff" }}
              dataKey="completion"
              dot={{ fill: "#10b981", r: 3, stroke: "#ffffff" }}
              stroke="#10b981"
              strokeWidth={2.5}
              type="monotone"
            />
          </LineChart>
        </ResponsiveContainer>
      </div>

      <div className="mt-3 grid grid-cols-3 gap-2 text-center">
        {["确定性计算", "Evidence 绑定", "报告验证"].map((label) => (
          <div
            className="rounded-lg bg-slate-50 px-2 py-2 text-[10px] text-slate-500"
            key={label}
          >
            {label}
          </div>
        ))}
      </div>
    </section>
  );
}
