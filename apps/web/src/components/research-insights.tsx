"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useMemo, useState } from "react";
import {
  Area,
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import { errorMessage, fetchApi } from "@/lib/api-client";
import {
  researchInsightsSchema,
  type ResearchInsights,
} from "@/lib/schemas";

type ChartRange = "3M" | "1Y" | "3Y" | "MAX";

const ranges: { label: string; value: ChartRange }[] = [
  { label: "3个月", value: "3M" },
  { label: "1年", value: "1Y" },
  { label: "3年", value: "3Y" },
  { label: "全部", value: "MAX" },
];

function number(value: number | string | null, maximumFractionDigits = 2) {
  if (value === null) return "—";
  return new Intl.NumberFormat("zh-CN", { maximumFractionDigits }).format(Number(value));
}

function money(value: number | string | null, currency: string) {
  if (value === null) return "—";
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency,
    maximumFractionDigits: 2,
  }).format(Number(value));
}

function percent(value: number | string | null, digits = 1) {
  if (value === null) return "—";
  return new Intl.NumberFormat("zh-CN", {
    style: "percent",
    maximumFractionDigits: digits,
  }).format(Number(value));
}

function compact(value: number | string) {
  return new Intl.NumberFormat("zh-CN", {
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(Number(value));
}

function rangeStart(latest: string, range: ChartRange) {
  if (range === "MAX") return null;
  const start = new Date(`${latest}T00:00:00Z`);
  if (range === "3M") start.setUTCMonth(start.getUTCMonth() - 3);
  if (range === "1Y") start.setUTCFullYear(start.getUTCFullYear() - 1);
  if (range === "3Y") start.setUTCFullYear(start.getUTCFullYear() - 3);
  return start.toISOString().slice(0, 10);
}

function PriceTechnicalChart({ insights }: { insights: ResearchInsights }) {
  const [range, setRange] = useState<ChartRange>("1Y");
  const chart = insights.priceChart;
  const points = useMemo(() => {
    const latest = chart.points.at(-1)?.date;
    if (!latest) return [];
    const start = rangeStart(latest, range);
    return chart.points
      .filter((point) => start === null || point.date >= start)
      .map((point) => ({
        ...point,
        adjustedClose: Number(point.adjustedClose),
        ma20: point.ma20 === null ? null : Number(point.ma20),
        ma50: point.ma50 === null ? null : Number(point.ma50),
      }));
  }, [chart.points, range]);
  const stats = chart.rangeStats.find((item) => item.range === range) ?? null;
  const positive = Number(stats?.periodReturn ?? 0) >= 0;

  return (
    <section className="surface-card overflow-hidden p-5 sm:p-6" aria-labelledby="price-technical-title">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-[10px] font-bold uppercase tracking-[0.16em] text-emerald-600">Market chart</span>
            <span className="rounded-full bg-slate-100 px-2 py-1 text-[9px] text-slate-500">{chart.symbol}</span>
          </div>
          <h2 className="mt-2 text-xl font-semibold text-slate-950" id="price-technical-title">价格与技术趋势</h2>
          <p className="mt-1 text-[11px] leading-5 text-slate-500">复权价格、成交量与移动均线均来自本次研究的数据快照。</p>
        </div>
        <div className="flex w-fit rounded-xl bg-slate-100 p-1" aria-label="价格图表范围">
          {ranges.map((item) => (
            <button
              className={`rounded-lg px-3 py-1.5 text-[10px] font-semibold transition ${range === item.value ? "bg-white text-slate-950 shadow-sm" : "text-slate-500 hover:text-slate-950"}`}
              key={item.value}
              onClick={() => setRange(item.value)}
              type="button"
            >
              {item.label}
            </button>
          ))}
        </div>
      </div>

      {points.length > 0 && stats ? (
        <>
          <div className="mt-6 grid grid-cols-2 gap-x-5 gap-y-4 border-y border-slate-100 py-4 sm:grid-cols-4">
            <div><p className="text-[10px] text-slate-500">最新价格</p><p className="mt-1 text-lg font-semibold text-slate-950">{money(stats.lastPrice, chart.currency)}</p></div>
            <div><p className="text-[10px] text-slate-500">区间涨跌</p><p className={`mt-1 text-lg font-semibold ${positive ? "text-emerald-600" : "text-rose-600"}`}>{percent(stats.periodReturn)}</p></div>
            <div><p className="text-[10px] text-slate-500">区间高 / 低</p><p className="mt-1 text-sm font-semibold text-slate-800">{money(stats.high, chart.currency)} / {money(stats.low, chart.currency)}</p></div>
            <div><p className="text-[10px] text-slate-500">平均成交量</p><p className="mt-1 text-sm font-semibold text-slate-800">{compact(stats.averageVolume)}</p></div>
          </div>
          <div className="mt-4 h-[390px] min-w-0">
            <ResponsiveContainer height="100%" width="100%">
              <ComposedChart data={points} margin={{ left: 4, right: 8, top: 12, bottom: 0 }}>
                <defs>
                  <linearGradient id="priceArea" x1="0" x2="0" y1="0" y2="1">
                    <stop offset="0%" stopColor="#10b981" stopOpacity={0.2} />
                    <stop offset="100%" stopColor="#10b981" stopOpacity={0.01} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="#eef2f5" vertical={false} />
                <XAxis
                  axisLine={false}
                  dataKey="date"
                  minTickGap={44}
                  tick={{ fill: "#94a3b8", fontSize: 10 }}
                  tickFormatter={(value: string) => value.slice(0, 7)}
                  tickLine={false}
                />
                <YAxis
                  axisLine={false}
                  domain={["auto", "auto"]}
                  tick={{ fill: "#94a3b8", fontSize: 10 }}
                  tickFormatter={(value: number) => number(value, 0)}
                  tickLine={false}
                  width={46}
                  yAxisId="price"
                />
                <YAxis hide domain={[0, "dataMax * 4"]} orientation="right" yAxisId="volume" />
                <Tooltip
                  contentStyle={{ background: "#fff", border: 0, borderRadius: 12, boxShadow: "0 14px 34px rgba(15,23,42,.12)", fontSize: 11 }}
                  formatter={(value, name) => name === "成交量" ? compact(Number(value)) : money(Number(value), chart.currency)}
                  labelFormatter={(label) => `交易日 ${label}`}
                />
                <Legend iconType="plainline" wrapperStyle={{ fontSize: 10, color: "#64748b" }} />
                <Bar dataKey="volume" fill="#dbe4ea" name="成交量" opacity={0.6} yAxisId="volume" />
                <Area dataKey="adjustedClose" fill="url(#priceArea)" name="复权价" stroke="#10b981" strokeWidth={2.2} type="monotone" yAxisId="price" />
                <Line connectNulls={false} dataKey="ma20" dot={false} name="MA20" stroke="#3b82f6" strokeWidth={1.3} type="monotone" yAxisId="price" />
                <Line connectNulls={false} dataKey="ma50" dot={false} name="MA50" stroke="#f59e0b" strokeWidth={1.3} type="monotone" yAxisId="price" />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
          <div className="mt-3 flex flex-col gap-2 text-[10px] text-slate-500 sm:flex-row sm:items-center sm:justify-between">
            <p>{chart.technicalSummary.signal} · 相对 MA20 {percent(chart.technicalSummary.priceVsMa20)} · 相对 MA50 {percent(chart.technicalSummary.priceVsMa50)}</p>
            <p>来源：{chart.provider ?? "未提供"} · 截至 {chart.asOfDate ?? "—"}</p>
          </div>
          <details className="mt-3 text-[10px] text-slate-500"><summary className="cursor-pointer font-semibold text-slate-600">查看计算口径</summary><p className="mt-2 leading-5">{chart.methodology}</p></details>
        </>
      ) : (
        <p className="mt-6 rounded-xl bg-amber-50 p-4 text-xs text-amber-700">本次研究没有可展示的价格序列。</p>
      )}
    </section>
  );
}

function heatColor(change: number) {
  if (change >= 0.25) return "bg-emerald-600 text-white";
  if (change >= 0.1) return "bg-emerald-100 text-emerald-800";
  if (change > -0.1) return "bg-slate-100 text-slate-800";
  if (change > -0.25) return "bg-rose-100 text-rose-800";
  return "bg-rose-500 text-white";
}

function ValuationWorkbench({ insights }: { insights: ResearchInsights }) {
  const valuation = insights.valuation;
  const marketGrowth = valuation.marketImpliedRevenueGrowth;
  const baseGrowth = valuation.baseRevenueGrowth;
  const expectationGap = valuation.marketImpliedGrowthGap === null
    ? null
    : Number(valuation.marketImpliedGrowthGap);

  return (
    <section className="surface-card p-5 sm:p-6" aria-labelledby="valuation-workbench-title">
      <span className="text-[10px] font-bold uppercase tracking-[0.16em] text-blue-600">Valuation lab</span>
      <h2 className="mt-2 text-xl font-semibold text-slate-950" id="valuation-workbench-title">估值解释与市场预期</h2>
      <p className="mt-1 text-[11px] leading-5 text-slate-500">把“目标价”拆成假设：市场当前价格究竟要求多高的增长，以及假设改变后价格如何变化。</p>

      {valuation.available && valuation.sensitivity ? (
        <>
          <div className="mt-6 grid gap-5 border-y border-slate-100 py-5 md:grid-cols-3">
            <div><p className="text-[10px] text-slate-500">市场隐含收入增长</p><p className="mt-2 text-2xl font-semibold text-slate-950">{percent(marketGrowth)}</p><p className="mt-1 text-[10px] text-slate-500">按基准利润率与倍数反推</p></div>
            <div><p className="text-[10px] text-slate-500">模型基准增长</p><p className="mt-2 text-2xl font-semibold text-slate-950">{percent(baseGrowth)}</p><p className={`mt-1 text-[10px] ${(expectationGap ?? 0) > 0 ? "text-amber-700" : "text-emerald-700"}`}>{expectationGap === null ? "无法比较" : `市场要求比模型${expectationGap >= 0 ? "高" : "低"} ${percent(Math.abs(expectationGap))}`}</p></div>
            <div><p className="text-[10px] text-slate-500">当前价相对加权价值</p><p className={`mt-2 text-2xl font-semibold ${Number(valuation.premiumDiscountToWeightedValue ?? 0) > 0 ? "text-rose-600" : "text-emerald-600"}`}>{percent(valuation.premiumDiscountToWeightedValue)}</p><p className="mt-1 text-[10px] text-slate-500">正数为溢价，负数为折价</p></div>
          </div>

          <div className="mt-6 overflow-x-auto">
            <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
              <div><h3 className="text-sm font-semibold text-slate-900">股价敏感性矩阵</h3><p className="mt-1 text-[10px] text-slate-500">行：收入增长率 · 列：估值倍数 · 单位：{valuation.currency}/股</p></div>
              <p className="text-[10px] text-slate-500">基准 EBITDA 利润率 {percent(valuation.baseEbitdaMargin)}</p>
            </div>
            <table className="w-full min-w-[620px] border-separate border-spacing-1 text-center text-[11px]">
              <thead><tr><th className="px-2 py-2 text-left font-medium text-slate-500">增长 \ 倍数</th>{valuation.sensitivity.valuationMultiples.map((multiple) => <th className="px-2 py-2 font-semibold text-slate-700" key={String(multiple)}>{number(multiple, 1)}×</th>)}</tr></thead>
              <tbody>
                {valuation.sensitivity.rows.map((row) => (
                  <tr key={String(row.revenueGrowthRate)}>
                    <th className="px-2 py-3 text-left font-semibold text-slate-700">{percent(row.revenueGrowthRate)}</th>
                    {row.impliedPrices.map((price, index) => <td className={`rounded-lg px-2 py-3 font-semibold transition hover:scale-[1.03] ${heatColor(Number(row.upsideDownside[index]))}`} key={`${String(row.revenueGrowthRate)}-${String(valuation.sensitivity?.valuationMultiples[index])}`}>{money(price, valuation.currency)}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <details className="mt-5 rounded-xl bg-slate-50 p-4 text-[10px] text-slate-600"><summary className="cursor-pointer font-semibold text-slate-800">公式、假设与限制</summary><p className="mt-3 leading-5">{valuation.formula}</p><ul className="mt-2 list-disc space-y-1 pl-4 leading-5">{valuation.caveats.map((item) => <li key={item}>{item}</li>)}</ul></details>
        </>
      ) : (
        <div className="mt-6 rounded-xl bg-amber-50 p-4 text-xs leading-6 text-amber-700"><p className="font-semibold">暂时无法生成完整估值解释</p><p>{valuation.unavailableReason}</p><p className="mt-2 text-[10px]">{valuation.formula}</p></div>
      )}
    </section>
  );
}

function PeerComparison({ insights }: { insights: ResearchInsights }) {
  const peers = insights.peers;
  return (
    <section className="surface-card p-5 sm:p-6" aria-labelledby="peer-comparison-title">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div><span className="text-[10px] font-bold uppercase tracking-[0.16em] text-violet-600">Peer set</span><h2 className="mt-2 text-xl font-semibold text-slate-950" id="peer-comparison-title">同行公司比较</h2><p className="mt-1 text-[11px] leading-5 text-slate-500">{peers.groupLabel ?? "同行组待配置"} · {peers.coverageMessage}</p></div>
        {peers.available ? <span className="w-fit rounded-full bg-violet-50 px-3 py-1.5 text-[10px] font-semibold text-violet-700">覆盖 {peers.availableCount}/{peers.configuredCount}</span> : null}
      </div>

      {peers.rows.length > 0 ? (
        <div className="mt-6 overflow-x-auto">
          <table className="w-full min-w-[760px] text-left text-[11px]">
            <thead className="text-slate-500"><tr className="border-b border-slate-100"><th className="px-2 py-3 font-medium">公司</th><th className="px-2 py-3 font-medium">当前价</th><th className="px-2 py-3 font-medium">加权价值</th><th className="px-2 py-3 font-medium">潜在空间</th><th className="px-2 py-3 font-medium">收入 CAGR</th><th className="px-2 py-3 font-medium">经营利润率</th><th className="px-2 py-3 font-medium">数据可靠度</th><th className="px-2 py-3 font-medium">截至</th></tr></thead>
            <tbody>
              {peers.rows.map((row) => (
                <tr className={`border-b border-slate-50 transition hover:bg-slate-50 ${row.target ? "bg-emerald-50/50" : ""}`} key={row.symbol}>
                  <td className="px-2 py-3"><Link className="font-bold text-slate-950 hover:text-emerald-600" href={`/research/${row.researchId}/reports/${row.reportVersion}`}>{row.symbol}</Link>{row.target ? <span className="ml-2 rounded bg-emerald-100 px-1.5 py-0.5 text-[8px] font-semibold text-emerald-700">本报告</span> : null}</td>
                  <td className="px-2 py-3 text-slate-700">{money(row.currentPrice, insights.valuation.currency)}</td>
                  <td className="px-2 py-3 text-slate-700">{money(row.weightedImpliedPrice, insights.valuation.currency)}</td>
                  <td className={`px-2 py-3 font-semibold ${Number(row.baseCaseUpside ?? 0) >= 0 ? "text-emerald-600" : "text-rose-600"}`}>{percent(row.baseCaseUpside)}</td>
                  <td className="px-2 py-3 text-slate-700">{percent(row.revenueCagr)}</td>
                  <td className="px-2 py-3 text-slate-700">{percent(row.operatingMargin)}</td>
                  <td className="px-2 py-3 text-slate-700">{percent(row.dataQuality)}</td>
                  <td className="px-2 py-3 text-slate-500">{row.asOfDate}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="mt-5 rounded-xl bg-slate-50 p-4 text-xs leading-6 text-slate-600">{peers.coverageMessage}</p>
      )}
      <details className="mt-4 text-[10px] text-slate-500"><summary className="cursor-pointer font-semibold text-slate-600">查看同行选择口径</summary><p className="mt-2 leading-5">{peers.methodology}</p></details>
    </section>
  );
}

export function ResearchInsightsPanel({ researchId, version }: { researchId: string; version: number }) {
  const insights = useQuery({
    queryKey: ["research", researchId, "insights", version],
    queryFn: () => fetchApi(
      `/api/research/${researchId}/insights?reportVersion=${version}`,
      researchInsightsSchema,
    ),
  });

  if (insights.isPending) {
    return <section className="surface-card p-6 text-xs text-slate-500">正在生成价格、估值与同行分析视图…</section>;
  }
  if (insights.isError || !insights.data) {
    return <section className="rounded-2xl bg-amber-50 p-5 text-xs text-amber-700" role="alert"><p className="font-semibold">扩展分析暂时无法读取</p><p className="mt-1">{errorMessage(insights.error)}</p><button className="mt-3 rounded-lg bg-white px-3 py-2 font-semibold shadow-sm" onClick={() => void insights.refetch()} type="button">重新加载</button></section>;
  }

  return (
    <div className="space-y-6">
      <PriceTechnicalChart insights={insights.data} />
      <ValuationWorkbench insights={insights.data} />
      <PeerComparison insights={insights.data} />
    </div>
  );
}
