package com.aiquantresearch.api.research.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WarningResponseTest {

    @Test
    void translatesShortMarketHistoryIntoReadableChinese() {
        WarningResponse warning = WarningResponse.fromMessage(
                "MARKET_HISTORY_SHORTER_THAN_REQUESTED"
        );

        assertThat(warning.code()).isEqualTo("MARKET_HISTORY_SHORTER_THAN_REQUESTED");
        assertThat(warning.message()).contains("实际可用日期", "未补造缺失历史");
    }

    @Test
    void preservesUnknownWarningsWithoutInventingAClassification() {
        WarningResponse warning = WarningResponse.fromMessage("UNKNOWN_LIMITATION");

        assertThat(warning.code()).isEqualTo("RESEARCH_WARNING");
        assertThat(warning.message()).isEqualTo("UNKNOWN_LIMITATION");
    }
}
