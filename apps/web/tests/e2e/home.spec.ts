import { expect, test } from "@playwright/test";

test("首页呈现专业研究工作台与明确 DEMO 语义", async ({ page }) => {
  await page.goto("/");

  await expect(
    page.getByRole("heading", { name: /从数据与证据出发/ }),
  ).toBeVisible();
  await expect(
    page.getByText("DEMO DATA — NOT REAL MARKET DATA"),
  ).toBeVisible();
  await expect(page.getByText("dataMode: MOCK")).toBeVisible();
  await expect(page.getByLabel("证券代码")).toHaveValue("MU");
  await expect(
    page.getByRole("button", { name: "创建 DEMO 研究" }),
  ).toBeVisible();
});

test("健康接口返回 Web Mock 状态", async ({ request }) => {
  const response = await request.get("/api/health");
  const payload = await response.json();

  expect(response.ok()).toBeTruthy();
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
