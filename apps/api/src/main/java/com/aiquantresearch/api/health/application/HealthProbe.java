package com.aiquantresearch.api.health.application;

public interface HealthProbe {

    String componentName();

    boolean critical();

    String probe();
}
