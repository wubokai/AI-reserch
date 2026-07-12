package com.aiquantresearch.api.research.web;

import java.util.List;

public record WarningResponse(String code, String message, List<String> evidenceIds) {
    static WarningResponse fromMessage(String message) {
        return switch (message) {
            case "MARKET_HISTORY_SHORTER_THAN_REQUESTED" -> new WarningResponse(
                    message,
                    "这只证券的上市或可用行情历史短于所选周期；报告已按实际可用日期计算，未补造缺失历史。",
                    List.of()
            );
            case "FILING_CONTENT_TRUNCATED" -> new WarningResponse(
                    message,
                    "部分 SEC 公告超过安全处理边界；系统只索引受控范围，未处理部分不会被当作报告证据。",
                    List.of()
            );
            case "FUNDAMENTAL_METRICS_UNAVAILABLE" -> new WarningResponse(
                    message,
                    "部分财务指标没有可核验数据，报告会明确标记缺失而不会使用 0 代替。",
                    List.of()
            );
            case "REPORT_REPAIRED_ONCE" -> new WarningResponse(
                    message,
                    "报告在发布前经过一次受约束修复，所有保留结论仍需通过证据校验。",
                    List.of()
            );
            default -> new WarningResponse("RESEARCH_WARNING", message, List.of());
        };
    }
}
