package com.aiquantresearch.api.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class HttpAuditFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpAuditFilter.class);
    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}"
                    + "-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
    );
    private static final Pattern UNSAFE_PATH_CHARACTER = Pattern.compile("[^A-Za-z0-9_./{}-]");
    private static final Pattern KNOWN_RESEARCH_ROUTE = Pattern.compile(
            "^/api/v1/research(?:/\\{researchId}(?:/(?:status|retry|cancel|export"
                    + "|evidence(?:/search)?|reports(?:/[0-9]{1,10})?))?)?$"
    );

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            LOGGER.info(
                    "HTTP request completed method={} route={} status={} durationMs={}",
                    safeMethod(request.getMethod()),
                    safeRoute(request.getRequestURI()),
                    response.getStatus(),
                    durationMs
            );
        }
    }

    static String safeRoute(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return "/";
        }
        String normalized = UUID_SEGMENT.matcher(requestUri).replaceAll("/{researchId}");
        normalized = UNSAFE_PATH_CHARACTER.matcher(normalized).replaceAll("_");
        if (KNOWN_RESEARCH_ROUTE.matcher(normalized).matches()) {
            return normalized;
        }
        if (normalized.equals("/api/v1/health")
                || normalized.equals("/api/v1/providers/status")
                || normalized.equals("/api/v1/securities/search")
                || normalized.startsWith("/actuator/health")
                || normalized.equals("/actuator/prometheus")) {
            return normalized;
        }
        return "/unmatched";
    }

    private static String safeMethod(String method) {
        return method != null && method.matches("^[A-Z]{3,10}$") ? method : "UNKNOWN";
    }
}
