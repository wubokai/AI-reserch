# Phase 4 完整量化与 Gate G4 测试矩阵

日期：2026-07-10

状态：本地实现与验证完成；等待 GitHub Actions/Compose 远程终验

## 1. 交付范围

`calculationVersion=quant_v1` 保持不变，Python Analytics 仍是唯一量化计算方。Phase 4 在不改变
Phase 3 报告必需指标含义的前提下，把完整结果扩展为 73 个有序 Metric 加一个独立、可解释的
Trend 对象。所有结果都有 value/status、单位、样本、区间、版本、Snapshot lineage 和 warning。

| 模块 | 固定输出 | 关键口径 |
|---|---:|---|
| Return | 9 | 最新日收益、累计/年化/CAGR、20 日滚动、基准累计/年化及超额 |
| Risk | 13 | 日/年化/下行波动、最大回撤与持续期、VaR/CVaR、Sharpe/Sortino/Calmar、Beta/Alpha/Correlation |
| Technical | 16 + Trend | SMA/EMA/RSI/MACD/Bollinger/ATR/52 周位置/量能及五信号趋势分类 |
| Fundamental | 16 | 增长、利润率、杠杆/流动性、回报率、稀释与 CapEx 趋势 |
| Valuation | 9 | 市值、P/E、Forward P/E、P/S、P/B、EV、EV/Revenue、EV/EBITDA、FCF Yield |
| Scenario | 10 | BULL/BASE/BEAR raw/display price、upside/downside 与概率加权值 |

## 2. 黄金数据

| 数据集 | 独立锚点 | 验证内容 |
|---|---|---|
| `tests/fixtures/phase4_market_golden.json` | 五日收益循环 × 20，精确 100 个日收益；预期值由不导入生产模块的独立脚本复核 | 9 项 Return 与 13 项 Risk 的数值、100 日 VaR/CVaR 边界、60 日配对指标、版本和 lineage |
| 递增价格 100..299 | SMA20=289.5、SMA50=274.5、SMA200=199.5、ATR14=2、Trend score=5 | 完整窗口、Bollinger 样本标准差、RSI、52 周距离与 `STRONG_UPTREND` 五信号 |
| 四点价格 100/120/90/110 | Total Return=10%、Max Drawdown=-25%、Duration=2 | 收益符号、峰谷口径与 `OPEN_DRAWDOWN` |
| 多期标准化财务 fixture | Revenue growth=25%、ROIC=24%、CapEx slope=10；完整估值人工复核 | 16 项基本面与 9 项估值、同期间/同单位选择和财务/价格 lineage |

## 3. 数值与语义边界

| 类别 | 覆盖 |
|---|---|
| 请求形状 | 空/单点、缺字段、未知字段、numeric decimal、错误版本、记录上限 |
| 行情清洗 | 非正价格、负成交量、非法 OHLC、相同/冲突重复日期、乱序字节稳定 |
| 最小样本 | 20/50/200 技术窗口，30 个收益波动率，100 个收益 VaR/CVaR，60 个共同收益 Beta/Alpha/Correlation |
| 基准对齐 | 共同日期 inner join、59/60 边界、`BENCHMARK_ALIGNMENT_REDUCED_SAMPLE` |
| 数值分母 | 常量价格/基准、零波动、零回撤、零负债分母，禁止 NaN/Infinity/负零 |
| 财务语义 | EPS 跨零、负权益、零流动负债、异常税率、负 EBITDA、单位错配、重复财务冲突 |
| 能力矩阵 | ETF 公司基本面/公司估值统一 `NOT_APPLICABLE` + `ETF_NOT_APPLICABLE` |
| 情景 | 概率和/name set、负 EBITDA、负 raw equity 保留与 display floor、加权值边界 |
| 性质 | Drawdown <= 0；Volatility/VaR/CVaR >= 0；Correlation ∈ [-1,1]；重排不改结果 |

## 4. 跨语言与回归策略

- Python Pydantic 与共享 JSON Schema 都要求 `trend` 字段；不足 200 个价格时显式为 `null`。
- Java consumer 校验根版本、input hash、每个 Metric 的版本/样本/warnings 和 Trend 结构。
- Java 报告降级只检查 9 个既有报告必需指标。Forward P/E 等可选缺失仍被存储和披露，但不会把
  Phase 3 Mock 闭环误判为 `PARTIALLY_COMPLETED`。
- 新增 Java policy test 分别锁定“可选不可用不降级”和“必需指标缺失必须降级”。

## 5. 本地验证结果

| 检查 | 结果 |
|---|---|
| Ruff check + format | 通过 |
| strict mypy | 28 个 source/test 文件通过 |
| pytest | 41 passed，branch coverage 93.92%（门槛 90%） |
| Java Surefire | 144 passed |
| JSON Schema parse | 通过 |
| `git diff --check` | 通过 |

## 6. Gate G4

| Gate 条件 | 状态 | 证据 |
|---|---|---|
| 原始指标实现或明确 NOT_AVAILABLE/NOT_APPLICABLE | 本地通过 | 73 个有序 Metric、完整状态和 warning 测试 |
| 每项结果含样本、区间、版本和 warning | 本地通过 | 黄金集/契约断言与共享 Schema |
| Trend Classification 无 LLM 依赖 | 本地通过 | 纯 Python 五信号规则及 199/200、涨/跌/平序列测试 |
| Ruff、mypy、pytest 全通过 | 本地通过 | 41 passed，93.92% coverage |
| Java/Python consumer/provider 契约 | 本地通过 | 144 个 Java 单测含 Phase 4 contract/policy |
| Linux/Testcontainers/Compose 闭环 | 待远程 | GitHub Actions PR run 待生成 |

Gate G4 只有在远程 CI 和 Phase 3 Compose 回归均通过后才标记完成。
