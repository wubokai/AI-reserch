import { expect, test } from "@playwright/test";

test("首页呈现专业研究工作台与明确 DEMO 语义", async ({ page }) => {
  await page.goto("/");

  await expect(
    page.getByRole("heading", { name: "美股量化研究工作台" }),
  ).toBeVisible();
  await expect(
    page.getByText("DEMO DATA — NOT REAL MARKET DATA"),
  ).toBeVisible();
  await expect(page.getByText("dataMode: MOCK")).toBeVisible();
  await expect(page.getByLabel("证券代码")).toHaveValue("MU");
  await expect(
    page.getByRole("button", { name: "开始分析" }),
  ).toBeVisible();
});

test("健康接口返回 Web Mock 状态", async ({ request }) => {
  const response = await request.get("/api/health");
  const payload = await response.json();

  expect(response.ok()).toBeTruthy();
  expect(response.headers()["x-content-type-options"]).toBe("nosniff");
  expect(response.headers()["x-frame-options"]).toBe("DENY");
  expect(response.headers()["referrer-policy"]).toBe("no-referrer");
  expect(response.headers()["content-security-policy"]).toContain("object-src 'none'");
  expect(payload).toMatchObject({
    status: "UP",
    service: "web",
    dataMode: "MOCK",
  });
});

test("页头展示 Web、API 与 Analytics 三服务状态", async ({ page }) => {
  await page.route("**/api/system-health", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        status: "UP",
        timestamp: "2026-07-09T00:00:00.000Z",
        services: {
          web: { status: "UP", service: "web", dataMode: "MOCK" },
          api: { status: "UP", service: "api", dataMode: "MOCK" },
          analytics: { status: "UP", service: "analytics" },
        },
      }),
    });
  });

  await page.goto("/");

  await expect(
    page.getByLabel("Web ↑ · API ↑ · Analytics ↑"),
  ).toBeVisible();
});

test("移动端可打开只读 Provider 状态页", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/api/providers/status", (route) => route.fulfill({
    contentType: "application/json",
    body: JSON.stringify({
      status: "UP",
      dataMode: "MOCK",
      checkedAt: "2026-07-11T12:00:00Z",
      providers: [{ name: "Deterministic Mock Data", capabilities: ["MARKET_DATA"], mode: "MOCK", status: "UP", configured: true, lastCheckedAt: "2026-07-11T12:00:00Z", lastSuccessAt: "2026-07-11T12:00:00Z", latencyMs: 0, rateLimit: { limited: false, remaining: null, resetsAt: null }, message: "Fixed fixtures" }],
    }),
  }));
  await page.goto("/");
  await page.getByRole("navigation", { name: "移动导航" }).getByRole("link", { name: "数据源" }).click();

  await expect(page).toHaveURL(/\/providers$/);
  await expect(page.getByRole("heading", { name: "数据源状态" })).toBeVisible();
  await expect(page.getByText("Deterministic Mock Data")).toBeVisible();
});
