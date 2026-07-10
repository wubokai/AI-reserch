export const demoSecurities = ["MU", "NVDA", "RKLB"] as const;

export const coverageItems = [
  {
    title: "行情数据",
    description: "固定五年日线 adjusted OHLCV",
    state: "Mock 已连接",
  },
  {
    title: "公司基本面",
    description: "可重复的演示财务快照",
    state: "Mock 已连接",
  },
  {
    title: "Filing 与宏观",
    description: "模拟文件与宏观序列",
    state: "Mock 已连接",
  },
  {
    title: "Evidence Registry",
    description: "结论、数字与来源建立关联",
    state: "约束已启用",
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
