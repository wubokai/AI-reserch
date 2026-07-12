"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useRef, useState, type FormEvent } from "react";

import { ArrowRightIcon, SearchIcon } from "@/components/icons";
import { fetchApi, errorMessage } from "@/lib/api-client";
import { demoSecurities } from "@/lib/demo-data";
import {
  researchAcceptedSchema,
  researchRequestSchema,
  securitySearchResponseSchema,
  type ResearchRequest,
} from "@/lib/schemas";

type FieldErrors = Partial<Record<keyof ResearchRequest, string>>;
type AnalysisFlag =
  | "includeTechnicalAnalysis"
  | "includeFundamentalAnalysis"
  | "includeMacroAnalysis";

const initialValues: ResearchRequest = {
  symbol: "MU",
  companyName: undefined,
  locale: "zh-CN",
  query: "",
  benchmark: "SPY",
  period: "5y",
  reportDepth: "STANDARD",
  includeTechnicalAnalysis: true,
  includeFundamentalAnalysis: true,
  includeMacroAnalysis: true,
};

const analysisOptions: ReadonlyArray<{
  key: AnalysisFlag;
  title: string;
  description: string;
  locked?: boolean;
}> = [
  {
    key: "includeTechnicalAnalysis",
    title: "量化与技术",
    description: "收益、风险与技术指标 · 必选",
    locked: true,
  },
  {
    key: "includeFundamentalAnalysis",
    title: "基本面",
    description: "财务趋势与质量检查",
  },
  {
    key: "includeMacroAnalysis",
    title: "宏观环境",
    description: "免费宏观序列与数据限制",
  },
];

function toFieldErrors(
  issues: ReadonlyArray<{ path: PropertyKey[]; message: string }>,
) {
  const errors: FieldErrors = {};
  for (const issue of issues) {
    const field = issue.path[0];
    if (typeof field === "string" && field in initialValues) {
      const key = field as keyof ResearchRequest;
      errors[key] ??= issue.message;
    }
  }
  return errors;
}

export function ResearchForm() {
  const router = useRouter();
  const [values, setValues] = useState<ResearchRequest>(initialValues);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [showSecurityMatches, setShowSecurityMatches] = useState(false);
  const idempotencyKey = useRef<string | null>(null);
  const createResearch = useMutation({
    mutationFn: async (request: ResearchRequest) => {
      idempotencyKey.current ??= crypto.randomUUID();
      return fetchApi("/api/research", researchAcceptedSchema, {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey.current },
        body: JSON.stringify(request),
      });
    },
    onSuccess: (research) => {
      router.push(`/research/${research.researchId}`);
    },
  });
  const securities = useQuery({
    queryKey: ["securities", values.symbol ?? ""],
    queryFn: () => fetchApi(`/api/securities/search?q=${encodeURIComponent(values.symbol ?? "")}&limit=6`, securitySearchResponseSchema),
    enabled: showSecurityMatches && (values.symbol?.length ?? 0) >= 1,
    staleTime: 60_000,
  });

  function updateValues(updater: (current: ResearchRequest) => ResearchRequest) {
    idempotencyKey.current = null;
    createResearch.reset();
    setValues(updater);
  }

  function submitResearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const parsed = researchRequestSchema.safeParse(values);
    if (!parsed.success) {
      setErrors(toFieldErrors(parsed.error.issues));
      return;
    }
    setErrors({});
    createResearch.mutate(parsed.data);
  }

  function setFlag(key: AnalysisFlag, checked: boolean) {
    updateValues((current) => ({ ...current, [key]: checked }));
  }

  return (
    <form
      aria-busy={createResearch.isPending}
      className="surface-card"
      onSubmit={submitResearch}
    >
      <div className="flex items-center justify-between gap-4 px-5 pb-0 pt-5 sm:px-6 sm:pt-6">
        <div>
          <p className="text-base font-bold tracking-tight text-slate-950">研究设置</p>
          <p className="mt-1 text-xs text-slate-500">
            选择范围并输入你最关心的问题。
          </p>
        </div>
        <span className="rounded-full bg-emerald-50 px-3 py-1.5 text-[10px] font-bold tracking-[0.1em] text-emerald-700">
          FREE RESEARCH
        </span>
      </div>

      <div className="space-y-6 p-5 sm:p-6">
        <div>
          <label className="mb-2 block text-xs font-medium text-[#334155]" htmlFor="symbol">
            证券代码
          </label>
          <div className="relative">
            <SearchIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-[#94a3b8]" />
            <input
              aria-describedby={errors.symbol ? "symbol-error" : undefined}
              aria-invalid={Boolean(errors.symbol)}
              className="h-11 w-full rounded-lg border border-[#cbd5e1] bg-[#f8fafc] pl-10 pr-4 text-sm font-semibold uppercase tracking-[0.08em] text-slate-950 placeholder:text-[#94a3b8] focus:border-emerald-500"
              id="symbol"
              maxLength={10}
              onChange={(event) =>
                { setShowSecurityMatches(true); updateValues((current) => ({
                    ...current,
                    symbol: event.target.value.toUpperCase() || undefined,
                  })); }
              }
              value={values.symbol ?? ""}
            />
          </div>
          {errors.symbol ? <p className="mt-2 text-xs text-rose-700" id="symbol-error">{errors.symbol}</p> : null}
          {showSecurityMatches && securities.data?.items.length ? (
            <div aria-label="证券搜索结果" className="mt-2 overflow-hidden rounded-lg border border-[#cbd5e1] bg-[#f8fafc]">
              {securities.data.items.map((item) => <button className="flex w-full items-center justify-between gap-3 border-t border-[#e7ebef] px-3 py-2 text-left first:border-t-0 hover:bg-slate-50" key={item.securityId} onClick={() => { setShowSecurityMatches(false); updateValues((current) => ({ ...current, symbol: item.symbol, companyName: item.companyName })); }} type="button"><span><span className="text-xs font-semibold text-emerald-700">{item.symbol}</span><span className="ml-2 text-[11px] text-[#64748b]">{item.companyName}</span></span><span className="text-[10px] text-[#64748b]">{item.exchange}</span></button>)}
            </div>
          ) : null}
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <span className="text-[11px] text-slate-500">快捷选择</span>
            {demoSecurities.map((symbol) => (
              <button
                className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-medium text-slate-600 hover:-translate-y-0.5 hover:bg-emerald-50 hover:text-emerald-700"
                key={symbol}
                onClick={() => updateValues((current) => ({ ...current, symbol }))}
                type="button"
              >
                {symbol}
              </button>
            ))}
          </div>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <label className="block"><span className="mb-2 block text-xs font-medium text-[#334155]">公司名称（可选核对）</span><input className="h-10 w-full rounded-lg border border-[#cbd5e1] bg-[#f8fafc] px-3 text-xs text-[#1f2937]" maxLength={200} onChange={(event) => updateValues((current) => ({ ...current, companyName: event.target.value.trim() || undefined }))} placeholder="例如 Micron Technology, Inc." value={values.companyName ?? ""} /></label>
          <label className="block"><span className="mb-2 block text-xs font-medium text-[#334155]">报告语言</span><select className="h-10 w-full rounded-lg border border-[#cbd5e1] bg-[#f8fafc] px-3 text-xs text-[#1f2937]" onChange={(event) => updateValues((current) => ({ ...current, locale: event.target.value as ResearchRequest["locale"] }))} value={values.locale ?? "zh-CN"}><option value="zh-CN">简体中文</option><option value="en-US">English</option></select></label>
        </div>

        <div>
          <div className="mb-2 flex items-center justify-between gap-4">
            <label className="text-xs font-medium text-[#334155]" htmlFor="query">研究问题</label>
            <span className="text-[10px] text-[#94a3b8]">{values.query.length} / 4000</span>
          </div>
          <textarea
            aria-describedby={errors.query ? "query-error" : "query-help"}
            aria-invalid={Boolean(errors.query)}
            className="min-h-32 w-full resize-y rounded-lg border border-[#cbd5e1] bg-[#f8fafc] px-4 py-3 text-sm leading-6 text-[#111827] placeholder:text-[#94a3b8] focus:border-emerald-500"
            id="query"
            maxLength={4000}
            onChange={(event) => updateValues((current) => ({ ...current, query: event.target.value }))}
            placeholder="例如：分析这家公司的增长动力、周期风险、财务质量和未来主要观察因素"
            value={values.query}
          />
          {errors.query ? (
            <p className="mt-2 text-xs text-rose-700" id="query-error">{errors.query}</p>
          ) : (
            <p className="mt-2 text-[11px] text-[#64748b]" id="query-help">
              系统先完成取数、计算和 Evidence 注册，再生成受约束报告。
            </p>
          )}
        </div>

        <div className="grid gap-4 sm:grid-cols-3">
          <label className="block">
            <span className="mb-2 block text-xs font-medium text-[#334155]">基准</span>
            <select
              aria-label="基准"
              className="h-10 w-full rounded-lg border border-[#cbd5e1] bg-[#f8fafc] px-3 text-xs text-[#1f2937]"
              onChange={(event) => updateValues((current) => ({ ...current, benchmark: event.target.value as ResearchRequest["benchmark"] }))}
              value={values.benchmark}
            >
              <option value="SPY">SPY · 默认基准</option>
              <option value="QQQ">QQQ · 可选基准</option>
            </select>
          </label>
          <label className="block">
            <span className="mb-2 block text-xs font-medium text-[#334155]">研究周期</span>
            <select aria-label="研究周期" className="h-10 w-full rounded-lg border border-[#cbd5e1] bg-[#f8fafc] px-3 text-xs text-[#1f2937]" onChange={(event) => updateValues((current) => ({ ...current, period: event.target.value as ResearchRequest["period"] }))} value={values.period}>
              <option value="1y">1 年</option>
              <option value="3y">3 年</option>
              <option value="5y">5 年</option>
            </select>
            <span className="mt-2 block text-[10px] leading-4 text-[#64748b]">上市历史不足时自动按实际可用区间计算；少于 200 个交易日才会停止。</span>
          </label>
          <label className="block">
            <span className="mb-2 block text-xs font-medium text-[#334155]">研究深度</span>
            <select aria-label="研究深度" className="h-10 w-full rounded-lg border border-[#cbd5e1] bg-[#f8fafc] px-3 text-xs text-[#1f2937]" onChange={(event) => updateValues((current) => ({ ...current, reportDepth: event.target.value as ResearchRequest["reportDepth"] }))} value={values.reportDepth}>
              <option value="QUICK">快速 · 精简证据</option>
              <option value="STANDARD">标准 · 平衡</option>
              <option value="DEEP">深度 · 完整证据</option>
            </select>
          </label>
        </div>

        <fieldset>
          <legend className="mb-3 text-xs font-medium text-[#334155]">分析模块</legend>
          <div className="grid gap-3 md:grid-cols-3">
            {analysisOptions.map((option) => (
              <label className="soft-section flex min-h-20 items-start gap-3 p-3.5 hover:-translate-y-0.5 hover:bg-emerald-50/70" key={option.key}>
                <input
                  aria-label={option.title}
                  checked={values[option.key]}
                  className="mt-0.5 size-4 accent-emerald-600 disabled:cursor-not-allowed disabled:opacity-70"
                  disabled={option.locked}
                  onChange={(event) => setFlag(option.key, event.target.checked)}
                  type="checkbox"
                />
                <span>
                  <span className="block text-xs font-semibold text-[#1f2937]">{option.title}</span>
                  <span className="mt-1 block text-[11px] leading-4 text-[#64748b]">{option.description}</span>
                </span>
              </label>
            ))}
          </div>
        </fieldset>

        {createResearch.isError ? (
          <p className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-xs leading-5 text-rose-700" role="alert">
            {errorMessage(createResearch.error)}
          </p>
        ) : null}

        <div className="flex flex-col gap-3 pt-1 sm:flex-row sm:items-center sm:justify-between">
          <p className="max-w-md text-[11px] leading-5 text-slate-500">
            提交后自动保存进度；即使关闭页面，也可以从“历史报告”继续查看。
          </p>
          <button
            className="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-5 text-xs font-bold text-white shadow-[0_8px_18px_rgba(5,150,105,0.2)] hover:-translate-y-0.5 hover:bg-emerald-700 hover:shadow-[0_10px_22px_rgba(5,150,105,0.25)] disabled:cursor-wait disabled:opacity-60"
            disabled={createResearch.isPending}
            type="submit"
          >
            {createResearch.isPending ? "正在创建…" : "开始分析"}
            <ArrowRightIcon className="size-4" />
          </button>
        </div>
      </div>
    </form>
  );
}
