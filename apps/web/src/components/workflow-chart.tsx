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
    <section className="rounded-xl border border-[#20342b] bg-[#0c1713] p-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-semibold text-white">研究执行链路</p>
          <p className="mt-1 text-xs text-[#789085]">
            非市场曲线，仅展示 Mock 工作流阶段。
          </p>
        </div>
        <span className="rounded border border-[#2b4439] px-2 py-1 text-[10px] font-semibold text-[#8da79b]">
          WORKFLOW
        </span>
      </div>

      <div
        aria-label="Mock 研究工作流完成度示意图"
        className="mt-5 h-48 w-full"
      >
        <ResponsiveContainer height="100%" width="100%">
          <LineChart
            data={workflowData}
            margin={{ bottom: 0, left: -22, right: 8, top: 8 }}
          >
            <CartesianGrid stroke="#1b2d25" strokeDasharray="3 3" />
            <XAxis
              axisLine={false}
              dataKey="stage"
              fontSize={10}
              stroke="#71887d"
              tickLine={false}
            />
            <YAxis
              axisLine={false}
              domain={[0, 100]}
              fontSize={10}
              stroke="#52685e"
              tickLine={false}
              width={38}
            />
            <Tooltip
              contentStyle={{
                background: "#0a1410",
                border: "1px solid #294137",
                borderRadius: "8px",
                color: "#eef6f1",
                fontSize: "12px",
              }}
              cursor={{ stroke: "#355246" }}
              formatter={(value) => [String(value) + "%", "流程进度"]}
            />
            <Line
              activeDot={{ fill: "#a7f3d0", r: 5, stroke: "#07100d" }}
              dataKey="completion"
              dot={{ fill: "#6ee7b7", r: 3, stroke: "#07100d" }}
              stroke="#6ee7b7"
              strokeWidth={2}
              type="monotone"
            />
          </LineChart>
        </ResponsiveContainer>
      </div>

      <div className="mt-3 grid grid-cols-3 gap-2 text-center">
        {["确定性计算", "Evidence 绑定", "报告验证"].map((label) => (
          <div
            className="rounded-md border border-[#1d3028] bg-[#09130f] px-2 py-2 text-[10px] text-[#88a095]"
            key={label}
          >
            {label}
          </div>
        ))}
      </div>
    </section>
  );
}
