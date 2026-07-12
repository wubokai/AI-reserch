export const demoSecurities = ["MU", "NVDA", "RKLB", "ASTS"] as const;

export const coverageItems = [
  {
    title: "行情数据",
    description: "日线行情、成交量与最新可用收盘价",
    state: "已接入",
  },
  {
    title: "公司基本面",
    description: "SEC 标准化财务事实与派生指标",
    state: "已接入",
  },
  {
    title: "公告与宏观",
    description: "公司公告、利率与经济环境序列",
    state: "已接入",
  },
  {
    title: "Evidence Registry",
    description: "结论、数字与来源建立关联",
    state: "约束启用",
  },
] as const;

export type WorkflowPoint = {
  stage: string;
  completion: number;
};

export const workflowData: WorkflowPoint[] = [
  { stage: "输入", completion: 14 },
  { stage: "取数", completion: 36 },
  { stage: "计算", completion: 58 },
  { stage: "证据", completion: 78 },
  { stage: "报告", completion: 100 },
];

export const recentDemoResearch = [
  {
    symbol: "MU",
    question: "增长动力、周期风险与财务质量",
    status: "可演示",
    step: "完整 Mock 链路",
  },
  {
    symbol: "NVDA",
    question: "研究工作流与 Evidence 展示",
    status: "模板",
    step: "等待创建",
  },
  {
    symbol: "RKLB",
    question: "风险分类与情景输入展示",
    status: "模板",
    step: "等待创建",
  },
] as const;
