package com.aiquantresearch.api;

import com.aiquantresearch.api.shared.config.ApplicationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
@EnableConfigurationProperties(ApplicationProperties.class)
public class AiQuantResearchApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiQuantResearchApiApplication.class, args);
    }
}
