"use client";

import { useState } from "react";

type Format = "markdown" | "html" | "pdf";
type ExportState = "idle" | "downloading" | "success" | "error";

function filenameFrom(response: Response, fallback: string) {
  const disposition = response.headers.get("content-disposition") ?? "";
  const match = disposition.match(/filename\*?=(?:UTF-8''|\")?([^";]+)/i);
  return match?.[1] ? decodeURIComponent(match[1]) : fallback;
}

export function ReportExportCenter({ researchId, version, symbol, dataMode }: { researchId: string; version: number; symbol: string; dataMode: "REAL" | "MOCK" | "MIXED_TEST" }) {
  const [states, setStates] = useState<Record<Format, ExportState>>({ markdown: "idle", html: "idle", pdf: "idle" });
  const [error, setError] = useState<string | null>(null);

  async function download(format: Format) {
    setStates((current) => ({ ...current, [format]: "downloading" }));
    setError(null);
    try {
      const response = await fetch(`/api/research/${researchId}/export?format=${format}&reportVersion=${version}`);
      if (!response.ok) throw new Error(`导出失败（HTTP ${response.status}）`);
      const expectedType = format === "pdf" ? "application/pdf" : format === "html" ? "text/html" : "text/markdown";
      if (!response.headers.get("content-type")?.includes(expectedType)) throw new Error("导出响应类型不符合契约");
      const responseMode = response.headers.get("x-data-mode");
      if (responseMode && responseMode !== dataMode) throw new Error("导出数据模式不符合报告契约");
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = filenameFrom(response, `${symbol}-research-v${version}.${format === "markdown" ? "md" : format}`);
      anchor.click();
      URL.revokeObjectURL(url);
      setStates((current) => ({ ...current, [format]: "success" }));
    } catch (cause) {
      setStates((current) => ({ ...current, [format]: "error" }));
      setError(cause instanceof Error ? cause.message : "导出失败");
    }
  }

  return (
    <div className="shrink-0">
      <div className="grid grid-cols-3 gap-2">
        {(["markdown", "html", "pdf"] as const).map((format) => <button aria-label={format} className="min-w-20 rounded-xl bg-slate-100 px-3 py-2 text-xs font-semibold uppercase text-slate-700 hover:-translate-y-0.5 hover:bg-emerald-50 hover:text-emerald-700 disabled:cursor-wait disabled:opacity-60" disabled={states[format] === "downloading"} key={format} onClick={() => download(format)} type="button">{states[format] === "downloading" ? "…" : format}<span className="mt-1 block text-[8px] font-normal normal-case text-slate-400">{states[format] === "success" ? "已完成" : states[format] === "error" ? "失败" : "下载"}</span></button>)}
      </div>
      {error ? <p className="mt-2 text-right text-[10px] text-rose-600" role="alert">{error}</p> : null}
    </div>
  );
}
