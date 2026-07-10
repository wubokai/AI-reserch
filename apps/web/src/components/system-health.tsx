"use client";

import { useQuery } from "@tanstack/react-query";

import { systemHealthResponseSchema } from "@/lib/schemas";

async function fetchHealth() {
  const response = await fetch("/api/system-health", { cache: "no-store" });

  if (!response.ok) {
    throw new Error("Health endpoint unavailable");
  }

  return systemHealthResponseSchema.parse(await response.json());
}

export function SystemHealth() {
  const health = useQuery({
    queryKey: ["system-health"],
    queryFn: fetchHealth,
    refetchInterval: 30_000,
  });

  const services = health.data?.services;
  const label = health.isPending
    ? "检查服务"
    : health.isError || !services
      ? "健康检查不可用"
      : [
          `Web ${services.web.status === "UP" ? "↑" : "↓"}`,
          `API ${services.api.status === "UP" ? "↑" : "↓"}`,
          `Analytics ${services.analytics.status === "UP" ? "↑" : "↓"}`,
        ].join(" · ");
  const degraded = health.isError || health.data?.status === "DEGRADED";

  return (
    <div
      aria-label={label}
      className="hidden items-center gap-2 text-[11px] text-[#8ba298] sm:flex"
      title={label}
    >
      <span
        className={
          degraded
            ? "size-1.5 rounded-full bg-amber-300 shadow-[0_0_0_4px_rgba(252,211,77,0.08)]"
            : "size-1.5 rounded-full bg-emerald-300 shadow-[0_0_0_4px_rgba(110,231,183,0.08)]"
        }
      />
      <span>{label}</span>
    </div>
  );
}
