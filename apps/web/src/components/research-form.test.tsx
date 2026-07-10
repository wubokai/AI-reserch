import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { ResearchForm } from "@/components/research-form";

describe("ResearchForm", () => {
  it("拒绝过短的研究问题", async () => {
    const user = userEvent.setup();
    render(<ResearchForm />);

    await user.click(
      screen.getByRole("button", { name: "验证 DEMO 研究" }),
    );

    expect(
      await screen.findByText("研究问题至少需要 12 个字符"),
    ).toBeInTheDocument();
  });

  it("接受有效的 Mock 研究草稿但不声称执行真实研究", async () => {
    const user = userEvent.setup();
    render(<ResearchForm />);

    await user.type(
      screen.getByLabelText("研究问题"),
      "分析增长动力、周期风险、财务质量和未来主要观察因素",
    );
    await user.click(
      screen.getByRole("button", { name: "验证 DEMO 研究" }),
    );

    expect(await screen.findByRole("status")).toHaveTextContent(
      "不会执行真实研究或产生金融结论",
    );
  });
});
