package com.aiquantresearch.api.research.provider.sec;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SecSecurityMasterSynchronizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesOfficialExchangeCatalogAndKeepsSpcx() throws Exception {
        var root = objectMapper.readTree("""
                {
                  "fields": ["cik", "name", "ticker", "exchange"],
                  "data": [
                    [1181412, "SPACE EXPLORATION TECHNOLOGIES CORP", "SPCX", "Nasdaq"],
                    [1045810, "NVIDIA CORP", "NVDA", "Nasdaq Global Select Market"],
                    [0, "Malformed", "bad ticker", ""]
                  ]
                }
                """);

        var identities = SecSecurityMasterSynchronizer.parseCatalog(root);

        assertThat(identities).extracting(
                SecSecurityMasterSynchronizer.SecurityIdentity::symbol,
                SecSecurityMasterSynchronizer.SecurityIdentity::exchange,
                SecSecurityMasterSynchronizer.SecurityIdentity::cik
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple("SPCX", "NASDAQ", "1181412"),
                org.assertj.core.groups.Tuple.tuple("NVDA", "NASDAQ", "1045810")
        );
    }
}
