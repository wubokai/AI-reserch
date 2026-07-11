package com.aiquantresearch.api.research.worker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class ResearchWorkerConfiguration {

    @Bean(destroyMethod = "shutdown")
    ExecutorService researchWorkerExecutor(WorkerProperties properties) {
        return Executors.newFixedThreadPool(
                properties.concurrency(),
                Thread.ofPlatform().name("research-worker-", 0).factory()
        );
    }
}
