package com.aiquantresearch.api.research.analytics;

import com.fasterxml.jackson.databind.JsonNode;

public interface AnalyticsClient {

    JsonNode runFullAnalysis(JsonNode request);

    JsonNode runInsights(JsonNode request);
}
