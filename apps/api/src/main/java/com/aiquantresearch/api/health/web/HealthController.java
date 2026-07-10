package com.aiquantresearch.api.health.web;

import com.aiquantresearch.api.health.application.HealthService;
import com.aiquantresearch.api.health.application.HealthSnapshot;
import com.aiquantresearch.api.health.domain.ServiceStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/health", produces = MediaType.APPLICATION_JSON_VALUE)
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        HealthSnapshot snapshot = healthService.currentHealth();
        HealthResponse response = HealthResponse.from(snapshot);
        return snapshot.status() == ServiceStatus.DOWN
                ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response)
                : ResponseEntity.ok(response);
    }
}
