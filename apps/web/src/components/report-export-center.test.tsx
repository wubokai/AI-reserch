import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ReportExportCenter } from "@/components/report-export-center";

describe("ReportExportCenter", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("显示导出成功状态并使用服务端文件名", async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    const createObjectURL = vi.fn(() => "blob:report");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", { ...URL, createObjectURL, revokeObjectURL });
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockResolvedValue(new Response("# report", {
      status: 200,
      headers: { "Content-Disposition": "attachment; filename=MU-report.md", "Content-Type": "text/markdown", "X-Data-Mode": "MOCK" },
    })));
    render(<ReportExportCenter dataMode="MOCK" researchId="11111111-1111-4111-8111-111111111111" symbol="MU" version={1} />);

    await userEvent.click(screen.getByRole("button", { name: "markdown" }));

    expect(await screen.findByText("已完成")).toBeInTheDocument();
    expect(click).toHaveBeenCalledOnce();
    expect(createObjectURL).toHaveBeenCalledOnce();
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:report");
    click.mockRestore();
  });

  it("显示安全的导出失败反馈", async () => {
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 503 })));
    render(<ReportExportCenter dataMode="MOCK" researchId="11111111-1111-4111-8111-111111111111" symbol="MU" version={1} />);

    await userEvent.click(screen.getByRole("button", { name: "pdf" }));

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("HTTP 503"));
  });
});
