import type { ZodType } from "zod";

import { problemDetailsSchema } from "@/lib/schemas";

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiClientError";
    this.status = status;
    this.code = code;
  }
}

export async function fetchApi<T>(
  url: string,
  schema: ZodType<T>,
  init?: RequestInit,
): Promise<T> {
  const response = await fetch(url, {
    cache: "no-store",
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
  });

  const payload: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    const problem = problemDetailsSchema.safeParse(payload);
    throw new ApiClientError(
      response.status,
      problem.success ? problem.data.code : "REQUEST_FAILED",
      problem.success ? problem.data.message : "请求暂时无法完成，请稍后重试。",
    );
  }

  return schema.parse(payload);
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "请求暂时无法完成，请稍后重试。";
}
