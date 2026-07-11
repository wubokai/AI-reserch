package com.aiquantresearch.api;

import com.aiquantresearch.api.shared.config.ApplicationProperties;
import com.aiquantresearch.api.research.analytics.AnalyticsProperties;
import com.aiquantresearch.api.research.llm.LlmProperties;
import com.aiquantresearch.api.research.report.ReportExportProperties;
import com.aiquantresearch.api.research.provider.fred.FredProperties;
import com.aiquantresearch.api.research.provider.tiingo.TiingoProperties;
import com.aiquantresearch.api.research.provider.runtime.ProviderRuntimeProperties;
import com.aiquantresearch.api.research.provider.sec.SecEdgarProperties;
import com.aiquantresearch.api.research.worker.WorkerProperties;
import com.aiquantresearch.api.shared.security.ServiceJwtProperties;
import com.aiquantresearch.api.research.retention.RetentionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching
@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties({
        ApplicationProperties.class,
        AnalyticsProperties.class,
        FredProperties.class,
        TiingoProperties.class,
        ProviderRuntimeProperties.class,
        ReportExportProperties.class,
        RetentionProperties.class,
        LlmProperties.class,
        SecEdgarProperties.class,
        ServiceJwtProperties.class,
        WorkerProperties.class
})
public class AiQuantResearchApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiQuantResearchApiApplication.class, args);
    }
}
