package com.aiquantresearch.api.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ResearchIdMdcFilter extends OncePerRequestFilter {

    public static final String RESEARCH_ID_MDC_KEY = "researchId";

    private static final Pattern RESEARCH_PATH = Pattern.compile(
            "^/api/v1/research/([0-9a-fA-F-]{36})(?:/.*)?$"
    );

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String researchId = extractResearchId(request.getRequestURI());
        String previousResearchId = MDC.get(RESEARCH_ID_MDC_KEY);
        if (researchId != null) {
            MDC.put(RESEARCH_ID_MDC_KEY, researchId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousResearchId == null) {
                MDC.remove(RESEARCH_ID_MDC_KEY);
            } else {
                MDC.put(RESEARCH_ID_MDC_KEY, previousResearchId);
            }
        }
    }

    private String extractResearchId(String path) {
        Matcher matcher = RESEARCH_PATH.matcher(path);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(1)).toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
