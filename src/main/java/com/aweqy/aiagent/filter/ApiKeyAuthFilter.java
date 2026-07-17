package com.aweqy.aiagent.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String ENABLED_PROPERTY = "app.api.security.enabled";
    private static final String HEADER_NAME_PROPERTY = "app.api.security.header-name";
    private static final String API_KEY_PROPERTY = "app.api.security.key";
    private static final String DEFAULT_HEADER_NAME = "X-API-KEY";

    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ApiKeyAuthFilter(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        String path = StringUtils.hasText(contextPath) && requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length())
                : requestUri;
        return !pathMatcher.match("/fund/**", path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean enabled = environment.getProperty(ENABLED_PROPERTY, Boolean.class, false);
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        String headerName = environment.getProperty(HEADER_NAME_PROPERTY, DEFAULT_HEADER_NAME);
        String apiKey = environment.getProperty(API_KEY_PROPERTY, "");
        String requestApiKey = request.getHeader(headerName);
        if (StringUtils.hasText(apiKey) && apiKey.equals(requestApiKey)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "timestamp", Instant.now(),
                "status", HttpStatus.UNAUTHORIZED.value(),
                "error", HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "message", "API Key 无效或缺失",
                "path", request.getRequestURI()
        ));
    }
}
