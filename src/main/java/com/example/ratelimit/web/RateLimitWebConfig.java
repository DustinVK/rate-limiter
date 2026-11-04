package com.example.ratelimit.web;

import com.example.ratelimit.NanoClock;
import com.example.ratelimit.RateLimitPolicy;
import com.example.ratelimit.RateLimitProperties;
import com.example.ratelimit.TokenBucketLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers the middleware for every route. Controllers need no changes; a
 * new endpoint is limited automatically with the read/write defaults.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitWebConfig implements WebMvcConfigurer {

    /** Paths that are never limited and need no API key. */
    private static final String[] EXCLUDED = {"/health", "/error"};

    private final RateLimitInterceptor interceptor;

    public RateLimitWebConfig(RateLimitPolicy policy, TokenBucketLimiter limiter, JsonMapper json, NanoClock clock) {
        this.interceptor = new RateLimitInterceptor(policy, limiter, json, clock);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).excludePathPatterns(EXCLUDED);
    }

    // Static, so this class can take the beans in its constructor without a cycle.

    @Bean
    static NanoClock nanoClock() {
        return System::nanoTime;
    }

    @Bean
    static RateLimitPolicy rateLimitPolicy(RateLimitProperties props) {
        return new RateLimitPolicy(props);
    }

    @Bean
    static TokenBucketLimiter tokenBucketLimiter(NanoClock clock, RateLimitProperties props) {
        return new TokenBucketLimiter(clock, props.maxBuckets());
    }

    @Bean
    static RouteOverrideCheck routeOverrideCheck(RateLimitProperties props) {
        return new RouteOverrideCheck(props);
    }
}
