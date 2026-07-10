import { readFile } from "node:fs/promises";

import { expect, test, type Page } from "@playwright/test";

import {
  phase3EvidencePage,
  phase3ReportEnvelope,
  phase3ResearchId,
} from "../../src/test/phase3-fixtures";

const now = "2026-07-10T12:00:00Z";

function json(payload: unknown, status = 200, headers: Record<string, string> = {}) {
  return {
    status,
    contentType: "application/json",
    headers,
    body: JSON.stringify(payload),
  };
}

async function mockClosedLoop(page: Page) {
  let statusCalls = 0;
  let completed = false;
  const exportedFormats: string[] = [];
  await page.route("**/api/system-health", (route) => route.fulfill(json({
    status: "UP",
    timestamp: now,
    services: {
      web: { status: "UP", service: "web", dataMode: "MOCK" },
      api: { status: "UP", service: "api", dataMode: "MOCK" },
      analytics: { status: "UP", service: "analytics" },
    },
  })));
  await page.route("**/api/research**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;

    if (path === "/api/research" && request.method() === "POST") {
      const body = request.postDataJSON() as Record<string, unknown>;
      expect(body.dataMode).toBeUndefined();
      expect(request.headers()["idempotency-key"]).toBeTruthy();
      await route.fulfill(json({
        researchId: phase3ResearchId,
        status: "QUEUED",
        dataMode: "MOCK",
        createdAt: now,
        links: { self: `/api/v1/research/${phase3ResearchId}`, status: `/api/v1/research/${phase3ResearchId}/status` },
      }, 202));
      return;
    }
    if (path === "/api/research" && request.method() === "GET") {
      const items = completed ? [{
        researchId: phase3ResearchId,
        title: "MU Mock 证据研究报告",
        query: "分析 MU 的增长动力、周期风险和财务质量",
        symbol: "MU",
        companyName: "Micron Technology, Inc.",
        benchmark: "SPY",
        status: "COMPLETED",
        progress: 100,
        reportDepth: "STANDARD",
        dataMode: "MOCK",
        latestReportVersion: 1,
        createdAt: now,
        updatedAt: now,
        completedAt: now,
      }] : [];
      await route.fulfill(json({
        items,
        page: {
          number: 0,
          size: 20,
          totalElements: items.length,
          totalPages: items.length === 0 ? 0 : 1,
          first: true,
          last: true,
        },
      }));
      return;
    }
    if (path.endsWith("/status")) {
      statusCalls += 1;
      completed = statusCalls > 1;
      await route.fulfill(json({
        researchId: phase3ResearchId,
        status: completed ? "COMPLETED" : "RUNNING_QUANT_ANALYSIS",
        progress: completed ? 100 : 65,
        ...(completed ? {} : { currentStep: "RUN_QUANT_ANALYSIS" }),
        completedSteps: completed ? 11 : 6,
        totalSteps: 11,
        cancellationRequested: false,
        dataMode: "MOCK",
        error: null,
        steps: [
          { step: "RESOLVE_SECURITY", status: "SUCCEEDED", attemptCount: 1, durationMs: 12, retryable: false, startedAt: now, completedAt: now, error: null },
          { step: "RUN_QUANT_ANALYSIS", status: completed ? "SUCCEEDED" : "RUNNING", attemptCount: 1, durationMs: completed ? 38 : null, retryable: false, startedAt: now, completedAt: completed ? now : null, error: null },
        ],
        updatedAt: now,
      }));
      return;
    }
    if (path.endsWith("/reports/1")) {
      await route.fulfill(json(phase3ReportEnvelope));
      return;
    }
    if (path.endsWith("/evidence")) {
      await route.fulfill(json(phase3EvidencePage));
      return;
    }
    if (path.endsWith("/export")) {
      const format = url.searchParams.get("format") ?? "pdf";
      exportedFormats.push(format);
      const contentType = format === "pdf" ? "application/pdf" : format === "html" ? "text/html" : "text/markdown";
      const body = format === "pdf"
        ? "%PDF-1.7\nDEMO DATA — NOT REAL MARKET DATA"
        : format === "html"
          ? "<!doctype html><html><body><h1>MU</h1><p>DEMO DATA — NOT REAL MARKET DATA</p></body></html>"
          : "# MU\n\nDEMO DATA — NOT REAL MARKET DATA";
      await route.fulfill({
        status: 200,
        contentType,
        headers: {
          "Content-Disposition": `attachment; filename=MU-report.${format === "markdown" ? "md" : format}`,
          ETag: `"${format}-etag"`,
          "X-Content-SHA256": "c".repeat(64),
          "X-Data-Mode": "MOCK",
          "X-Report-Version": "1",
        },
        body,
      });
      return;
    }
    if (path === `/api/research/${phase3ResearchId}`) {
      const isCompleted = completed;
      await route.fulfill(json({
        researchId: phase3ResearchId,
        title: "MU Mock 证据研究报告",
        query: "分析 MU 的增长动力、周期风险和财务质量",
        symbol: "MU",
        companyName: "Micron Technology, Inc.",
        benchmark: "SPY",
        status: isCompleted ? "COMPLETED" : "RUNNING_QUANT_ANALYSIS",
        progress: isCompleted ? 100 : 65,
        reportDepth: "STANDARD",
        dataMode: "MOCK",
        latestReportVersion: isCompleted ? 1 : null,
        createdAt: now,
        updatedAt: now,
        completedAt: isCompleted ? now : null,
        request: {
          symbol: "MU",
          query: "分析 MU 的增长动力、周期风险和财务质量",
          benchmark: "SPY",
          period: "5y",
          reportDepth: "STANDARD",
          includeTechnicalAnalysis: true,
          includeFundamentalAnalysis: true,
          includeMacroAnalysis: true,
          locale: "zh-CN",
          companyName: "Micron Technology, Inc.",
        },
        currentStep: isCompleted ? null : "RUN_QUANT_ANALYSIS",
        startedAt: now,
        cancellationRequested: false,
        lastError: null,
        latestReport: null,
        warnings: [{ code: "MOCK_DATA", message: "DEMO DATA — NOT REAL MARKET DATA" }],
        links: { self: `/api/v1/research/${phase3ResearchId}`, status: `/api/v1/research/${phase3ResearchId}/status`, evidence: `/api/v1/research/${phase3ResearchId}/evidence`, reports: `/api/v1/research/${phase3ResearchId}/reports`, export: `/api/v1/research/${phase3ResearchId}/export` },
      }));
      return;
    }
    await route.fulfill(json({ timestamp: now, status: 404, code: "ROUTE_NOT_FOUND", message: "not found", researchId: null }, 404));
  });

  return {
    exportedFormats: () => [...exportedFormats],
    statusCalls: () => statusCalls,
  };
}

async function downloadedBytes(page: Page, linkName: "markdown" | "html" | "pdf") {
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("link", { name: linkName, exact: true }).click();
  const download = await downloadPromise;
  const path = await download.path();
  if (!path) throw new Error(`${linkName} download did not produce a local file`);
  return { bytes: await readFile(path), filename: download.suggestedFilename() };
}

test("从创建、进度、报告和三格式导出到历史重开形成 Mock 闭环", async ({ page }) => {
  test.setTimeout(60_000);
  const mock = await mockClosedLoop(page);
  await page.goto("/");

  await page.getByLabel("研究问题").fill("分析 MU 的增长动力、周期风险和财务质量");
  await page.getByRole("button", { name: "创建 DEMO 研究" }).click();
  await expect(page).toHaveURL(new RegExp(`/research/${phase3ResearchId}$`));
  await expect(page.getByText("运行量化分析").first()).toBeVisible();
  await expect(page.getByRole("link", { name: "打开研究报告" })).toBeVisible({ timeout: 7_000 });

  const terminalStatusCallCount = mock.statusCalls();
  expect(terminalStatusCallCount).toBe(2);
  await page.waitForTimeout(2_200);
  expect(mock.statusCalls()).toBe(terminalStatusCallCount);

  const reportResponses = Promise.all([
    page.waitForResponse((response) => new URL(response.url()).pathname.endsWith("/reports/1")),
    page.waitForResponse((response) => new URL(response.url()).pathname.endsWith("/evidence")),
  ]);
  await page.getByRole("link", { name: "打开研究报告" }).click();
  await expect(page).toHaveURL(new RegExp(`/research/${phase3ResearchId}/reports/1$`));
  const [reportResponse, evidenceResponse] = await reportResponses;
  expect(reportResponse.ok()).toBe(true);
  expect(evidenceResponse.ok()).toBe(true);
  await expect(page.getByRole("heading", { name: "MU Mock 证据研究报告" })).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByText("DEMO DATA — NOT REAL MARKET DATA").first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "Bull / Base / Bear 情景" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "BULL", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "BASE", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "BEAR", exact: true })).toBeVisible();

  await page.getByRole("button", { name: "ev_MU_RETURN_01" }).first().click();
  await expect(page.getByRole("dialog", { name: "Evidence ev_MU_RETURN_01" })).toBeVisible();
  await page.getByRole("button", { name: "关闭 Evidence" }).click();

  const markdown = await downloadedBytes(page, "markdown");
  expect(markdown.filename).toBe("MU-report.md");
  expect(markdown.bytes.toString("utf8")).toContain("# MU");
  expect(markdown.bytes.toString("utf8")).toContain("DEMO DATA — NOT REAL MARKET DATA");

  const html = await downloadedBytes(page, "html");
  expect(html.filename).toBe("MU-report.html");
  expect(html.bytes.toString("utf8")).toContain("<!doctype html>");
  expect(html.bytes.toString("utf8")).toContain("DEMO DATA — NOT REAL MARKET DATA");

  const pdf = await downloadedBytes(page, "pdf");
  expect(pdf.filename).toBe("MU-report.pdf");
  expect(pdf.bytes.subarray(0, 8).toString("ascii")).toBe("%PDF-1.7");
  expect(mock.exportedFormats()).toEqual(["markdown", "html", "pdf"]);

  await page.getByRole("link", { name: "历史报告" }).click();
  await expect(page).toHaveURL(/\/research$/);
  await expect(page.getByRole("heading", { name: "研究历史" })).toBeVisible();
  await expect(page.getByText("MU Mock 证据研究报告")).toBeVisible();
  await page.getByRole("link", { name: "打开报告" }).click();
  await expect(page).toHaveURL(new RegExp(`/research/${phase3ResearchId}/reports/1$`));
  await expect(page.getByRole("heading", { name: "MU Mock 证据研究报告" })).toBeVisible({
    timeout: 10_000,
  });
});
