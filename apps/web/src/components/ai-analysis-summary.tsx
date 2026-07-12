import type { CanonicalResearchReport } from "@/lib/schemas";

const scenarioNames = {
  BULL: "乐观",
  BASE: "基准",
  BEAR: "谨慎",
} as const;

function formatPercent(value: number) {
  return new Intl.NumberFormat("zh-CN", {
    style: "percent",
    maximumFractionDigits: 1,
    signDisplay: "exceptZero",
  }).format(value);
}

function formatUnsignedPercent(value: number) {
  return new Intl.NumberFormat("zh-CN", {
    style: "percent",
    maximumFractionDigits: 1,
  }).format(value);
}

function formatMoney(value: number, currency: string) {
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency,
    maximumFractionDigits: 2,
  }).format(value);
}

export function buildReportSummary(report: CanonicalResearchReport) {
  const scenarios = report.scenarioAnalysis.scenarios;
  const totalProbability = scenarios.reduce(
    (sum, scenario) => sum + Number(scenario.probability),
    0,
  );
  const weightedChange = totalProbability > 0
    ? scenarios.reduce(
        (sum, scenario) =>
          sum + Number(scenario.probability) * Number(scenario.upsideDownside),
        0,
      ) / totalProbability
    : 0;
  const dominantScenario = [...scenarios].sort(
    (left, right) => Number(right.probability) - Number(left.probability),
  )[0];
  const direction = weightedChange >= 0.05
    ? "BULLISH"
    : weightedChange <= -0.05
      ? "BEARISH"
      : "NEUTRAL";
  const directionLabel = direction === "BULLISH"
    ? "偏积极"
    : direction === "BEARISH"
      ? "偏谨慎"
      : "中性观察";
  const trendLabel = direction === "BULLISH"
    ? "上涨倾向"
    : direction === "BEARISH"
      ? "存在下跌压力"
      : "更可能震荡";
  const currency = report.scenarioAnalysis.currency ?? "USD";
  const currentPrice = report.scenarioAnalysis.currentPrice === undefined
    ? null
    : Number(report.scenarioAnalysis.currentPrice);
  const weightedImpliedPrice = Number(report.scenarioAnalysis.weightedImpliedPrice);
  const currentPriceText = currentPrice === null
    ? ""
    : `当前市场价格为 ${formatMoney(currentPrice, currency)}；`;
  const currentSituation = `数据可靠度为 ${formatUnsignedPercent(report.dataQuality.score)}。${currentPriceText}综合三种情景后的参考值为 ${formatMoney(weightedImpliedPrice, currency)}，加权潜在变动为 ${formatPercent(weightedChange)}，因此当前前景判断为“${directionLabel}”。`;
  const futureView = dominantScenario
    ? `${scenarioNames[dominantScenario.name]}情景的权重最高（${formatUnsignedPercent(Number(dominantScenario.probability))}）。该情景对应 ${formatMoney(Number(dominantScenario.impliedPrice), currency)}，相对情景变动为 ${formatPercent(Number(dominantScenario.upsideDownside))}。`
    : "情景数据不足，暂时无法判断未来方向。";
  const bullScenario = scenarios.find((scenario) => scenario.name === "BULL");
  const bearScenario = scenarios.find((scenario) => scenario.name === "BEAR");
  const bullMethod = bullScenario?.valuationMethod === "EV_REVENUE"
    ? `EV/收入倍数 ${Number(bullScenario.valuationMultiple ?? bullScenario.evToEbitdaMultiple).toFixed(1)}×`
    : bullScenario
      ? `经营利润率 ${formatUnsignedPercent(Number(bullScenario.targetEbitdaMargin))}、EV/EBITDA 倍数 ${Number(bullScenario.valuationMultiple ?? bullScenario.evToEbitdaMultiple).toFixed(1)}×`
      : "";
  const opportunity = bullScenario
    ? `乐观情况下参考值为 ${formatMoney(Number(bullScenario.impliedPrice), currency)}，可能变动 ${formatPercent(Number(bullScenario.upsideDownside))}；关键假设是收入增长 ${formatUnsignedPercent(Number(bullScenario.revenueGrowth))}、${bullMethod}。`
    : "暂未识别出有足够证据支持的主要机会。";
  const risk = bearScenario
    ? `谨慎情况下参考值为 ${formatMoney(Number(bearScenario.impliedPrice), currency)}，可能变动 ${formatPercent(Number(bearScenario.upsideDownside))}；若收入增长和利润率走弱，下行风险会明显增加。`
    : "暂未识别出有足够证据支持的主要风险。";

  return {
    direction,
    directionLabel,
    trendLabel,
    weightedChange,
    currentSituation,
    futureView,
    opportunity,
    risk,
  };
}

export function AiAnalysisSummary({ report }: { report: CanonicalResearchReport }) {
  const summary = buildReportSummary(report);
  const tone = summary.direction === "BULLISH"
    ? {
        border: "border-emerald-300/25",
        background: "bg-emerald-300/[0.055]",
        text: "text-emerald-100",
        badge: "border-emerald-300/25 bg-emerald-300/10 text-emerald-100",
      }
    : summary.direction === "BEARISH"
      ? {
          border: "border-rose-300/25",
          background: "bg-rose-300/[0.045]",
          text: "text-rose-100",
          badge: "border-rose-300/25 bg-rose-300/10 text-rose-100",
        }
      : {
          border: "border-amber-300/25",
          background: "bg-amber-300/[0.045]",
          text: "text-amber-100",
          badge: "border-amber-300/25 bg-amber-300/10 text-amber-100",
        };

  return (
    <section
      aria-labelledby="ai-analysis-summary-title"
      className={`rounded-xl border ${tone.border} ${tone.background} p-5 sm:p-7`}
    >
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <p className="text-[10px] font-semibold uppercase tracking-[0.2em] text-emerald-200/75">
            AI Analysis
          </p>
          <h2 className="mt-2 text-2xl font-semibold text-white" id="ai-analysis-summary-title">
            AI 分析总结
          </h2>
          <p className="mt-2 text-xs leading-5 text-[#8da59a]">
            先说结论，再查看详细依据。方向来自已验证数据与三情景加权，不代表确定预测。
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <span className={`rounded-full border px-3 py-1.5 text-xs font-bold ${tone.badge}`}>
            前景：{summary.directionLabel}
          </span>
          <span className="rounded-full border border-[#355044] bg-[#0c1713] px-3 py-1.5 text-xs font-semibold text-[#dce8e2]">
            走势：{summary.trendLabel}
          </span>
          <span className="rounded-full border border-[#355044] bg-[#0c1713] px-3 py-1.5 text-xs font-semibold text-[#dce8e2]">
            加权空间：{formatPercent(summary.weightedChange)}
          </span>
        </div>
      </div>

      <div className="mt-6 grid gap-3 lg:grid-cols-2">
        <article className="rounded-lg border border-white/5 bg-black/10 p-4">
          <h3 className={`text-xs font-semibold ${tone.text}`}>当前怎么看</h3>
          <p className="mt-2 text-sm leading-6 text-[#dce8e2]">{summary.currentSituation}</p>
        </article>
        <article className="rounded-lg border border-white/5 bg-black/10 p-4">
          <h3 className={`text-xs font-semibold ${tone.text}`}>未来可能怎样</h3>
          <p className="mt-2 text-sm leading-6 text-[#dce8e2]">{summary.futureView}</p>
        </article>
        <article className="rounded-lg border border-emerald-300/10 bg-emerald-300/[0.025] p-4">
          <h3 className="text-xs font-semibold text-emerald-100">主要利好</h3>
          <p className="mt-2 text-sm leading-6 text-[#dce8e2]">{summary.opportunity}</p>
        </article>
        <article className="rounded-lg border border-rose-300/10 bg-rose-300/[0.02] p-4">
          <h3 className="text-xs font-semibold text-rose-100">最大风险</h3>
          <p className="mt-2 text-sm leading-6 text-[#dce8e2]">{summary.risk}</p>
        </article>
      </div>
    </section>
  );
}
