"use client";

import { useState, type FormEvent } from "react";

import { ArrowRightIcon, SearchIcon } from "@/components/icons";
import { demoSecurities } from "@/lib/demo-data";
import {
  researchRequestSchema,
  type ResearchRequest,
} from "@/lib/schemas";

type FieldErrors = Partial<Record<keyof ResearchRequest, string>>;
type AnalysisFlag =
  | "includeTechnicalAnalysis"
  | "includeFundamentalAnalysis"
  | "includeMacroAnalysis";

const initialValues: ResearchRequest = {
  symbol: "MU",
  query: "",
  benchmark: "SPY",
  period: "5y",
  reportDepth: "STANDARD",
  includeTechnicalAnalysis: true,
  includeFundamentalAnalysis: true,
  includeMacroAnalysis: true,
  dataMode: "MOCK",
};

const analysisOptions: ReadonlyArray<{
  key: AnalysisFlag;
  title: string;
  description: string;
}> = [
  {
    key: "includeTechnicalAnalysis",
    title: "量化与技术",
    description: "收益、风险与确定性指标",
  },
  {
    key: "includeFundamentalAnalysis",
    title: "基本面",
    description: "财务趋势与质量检查",
  },
  {
    key: "includeMacroAnalysis",
    title: "宏观环境",
    description: "演示宏观序列与限制",
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
  const [values, setValues] = useState<ResearchRequest>(initialValues);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string | null>(null);

  function submitResearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setNotice(null);

    const parsed = researchRequestSchema.safeParse(values);

    if (!parsed.success) {
      setErrors(toFieldErrors(parsed.error.issues));
      return;
    }

    setErrors({});
    setNotice(
      "模拟研究请求已通过本地校验。Phase 1 不会执行真实研究或产生金融结论。",
    );
  }

  function setFlag(key: AnalysisFlag, checked: boolean) {
    setValues((current) => ({ ...current, [key]: checked }));
  }

  return (
    <form
      className="rounded-xl border border-[#20342b] bg-[#0c1713] shadow-[0_18px_48px_rgba(0,0,0,0.2)]"
      onSubmit={submitResearch}
    >
      <div className="flex items-center justify-between gap-4 border-b border-[#1b2c25] px-5 py-4 sm:px-6">
        <div>
          <p className="text-sm font-semibold text-white">创建研究任务</p>
          <p className="mt-1 text-xs text-[#81988d]">
            先定义研究问题，再由数据与 Evidence 驱动报告。
          </p>
        </div>
        <span className="rounded-md border border-emerald-300/20 bg-emerald-300/[0.06] px-2 py-1 text-[10px] font-semibold tracking-[0.14em] text-emerald-200">
          MOCK ONLY
        </span>
      </div>

      <div className="space-y-6 p-5 sm:p-6">
        <div>
          <label
            className="mb-2 block text-xs font-medium text-[#b8c8c0]"
            htmlFor="symbol"
          >
            证券代码
          </label>
          <div className="relative">
            <SearchIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-[#60786d]" />
            <input
              aria-describedby={errors.symbol ? "symbol-error" : undefined}
              aria-invalid={Boolean(errors.symbol)}
              className="h-11 w-full rounded-lg border border-[#294137] bg-[#09120f] pl-10 pr-4 text-sm font-semibold uppercase tracking-[0.08em] text-white placeholder:text-[#52685e] focus:border-emerald-300/50"
              id="symbol"
              maxLength={10}
              onChange={(event) =>
                setValues((current) => ({
                  ...current,
                  symbol: event.target.value.toUpperCase(),
                }))
              }
              value={values.symbol}
            />
          </div>
          {errors.symbol ? (
            <p className="mt-2 text-xs text-rose-300" id="symbol-error">
              {errors.symbol}
            </p>
          ) : null}
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <span className="text-[11px] text-[#647b70]">演示证券</span>
            {demoSecurities.map((symbol) => (
              <button
                className="rounded border border-[#263c32] bg-[#101d18] px-2.5 py-1 text-[11px] font-medium text-[#a9bdb3] transition-colors hover:border-emerald-300/30 hover:text-emerald-100"
                key={symbol}
                onClick={() =>
                  setValues((current) => ({ ...current, symbol }))
                }
                type="button"
              >
                {symbol}
              </button>
            ))}
          </div>
        </div>

        <div>
          <div className="mb-2 flex items-center justify-between gap-4">
            <label
              className="text-xs font-medium text-[#b8c8c0]"
              htmlFor="query"
            >
              研究问题
            </label>
            <span className="text-[10px] text-[#5f756b]">
              {values.query.length} / 1200
            </span>
          </div>
          <textarea
            aria-describedby={errors.query ? "query-error" : "query-help"}
            aria-invalid={Boolean(errors.query)}
            className="min-h-32 w-full resize-y rounded-lg border border-[#294137] bg-[#09120f] px-4 py-3 text-sm leading-6 text-[#edf5f0] placeholder:text-[#52685e] focus:border-emerald-300/50"
            id="query"
            maxLength={1200}
            onChange={(event) =>
              setValues((current) => ({
                ...current,
                query: event.target.value,
              }))
            }
            placeholder="例如：分析这家公司的增长动力、周期风险、财务质量和未来主要观察因素"
            value={values.query}
          />
          {errors.query ? (
            <p className="mt-2 text-xs text-rose-300" id="query-error">
              {errors.query}
            </p>
          ) : (
            <p className="mt-2 text-[11px] text-[#657b71]" id="query-help">
              系统不会把问题直接交给模型猜测，将先完成取数、计算和 Evidence 注册。
            </p>
          )}
        </div>

        <div className="grid gap-4 sm:grid-cols-3">
          <label className="block">
            <span className="mb-2 block text-xs font-medium text-[#b8c8c0]">
              基准
            </span>
            <select
              className="h-10 w-full rounded-lg border border-[#294137] bg-[#09120f] px-3 text-xs text-[#d8e5de]"
              onChange={(event) =>
                setValues((current) => ({
                  ...current,
                  benchmark: event.target.value as ResearchRequest["benchmark"],
                }))
              }
              value={values.benchmark}
            >
              <option value="SPY">SPY · 默认基准</option>
              <option value="QQQ">QQQ · 可选基准</option>
            </select>
          </label>

          <label className="block">
            <span className="mb-2 block text-xs font-medium text-[#b8c8c0]">
              研究周期
            </span>
            <select
              className="h-10 w-full rounded-lg border border-[#294137] bg-[#09120f] px-3 text-xs text-[#d8e5de]"
              disabled
              value={values.period}
            >
              <option value="5y">5 年 · MVP</option>
            </select>
          </label>

          <label className="block">
            <span className="mb-2 block text-xs font-medium text-[#b8c8c0]">
              研究深度
            </span>
            <select
              className="h-10 w-full rounded-lg border border-[#294137] bg-[#09120f] px-3 text-xs text-[#d8e5de]"
              disabled
              value={values.reportDepth}
            >
              <option value="STANDARD">标准 · MVP</option>
            </select>
          </label>
        </div>

        <fieldset>
          <legend className="mb-3 text-xs font-medium text-[#b8c8c0]">
            分析模块
          </legend>
          <div className="grid gap-3 md:grid-cols-3">
            {analysisOptions.map((option) => (
              <label
                className="flex min-h-20 items-start gap-3 rounded-lg border border-[#263b32] bg-[#0a1410] p-3 transition-colors hover:border-[#365345]"
                key={option.key}
              >
                <input
                  checked={values[option.key]}
                  className="mt-0.5 size-4 accent-emerald-300"
                  onChange={(event) =>
                    setFlag(option.key, event.target.checked)
                  }
                  type="checkbox"
                />
                <span>
                  <span className="block text-xs font-semibold text-[#dbe8e1]">
                    {option.title}
                  </span>
                  <span className="mt-1 block text-[11px] leading-4 text-[#687f74]">
                    {option.description}
                  </span>
                </span>
              </label>
            ))}
          </div>
        </fieldset>

        {notice ? (
          <p
            className="rounded-lg border border-emerald-300/20 bg-emerald-300/[0.06] px-4 py-3 text-xs leading-5 text-emerald-100"
            role="status"
          >
            {notice}
          </p>
        ) : null}

        <div className="flex flex-col gap-3 border-t border-[#1b2c25] pt-5 sm:flex-row sm:items-center sm:justify-between">
          <p className="max-w-md text-[11px] leading-5 text-[#62796e]">
            创建操作目前只进行本地 Schema 校验。后续阶段接入 Java 异步任务 API。
          </p>
          <button
            className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-emerald-300 px-4 text-xs font-bold text-[#062219] transition-colors hover:bg-emerald-200"
            type="submit"
          >
            验证 DEMO 研究
            <ArrowRightIcon className="size-4" />
          </button>
        </div>
      </div>
    </form>
  );
}
