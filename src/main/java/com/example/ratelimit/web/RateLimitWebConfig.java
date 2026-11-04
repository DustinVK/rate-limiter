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

    private final RateLimitPolicy policy;
    private final TokenBucketLimiter limiter;
    private final JsonMapper json;
    private final NanoClock clock;

    public RateLimitWebConfig(RateLimitPolicy policy, TokenBucketLimiter limiter, JsonMapper json, NanoClock clock) {
        this.policy = policy;
        this.limiter = limiter;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiKeyInterceptor(policy, json)).excludePathPatterns(EXCLUDED).order(0);
        registry.addInterceptor(new RateLimitInterceptor(policy, limiter, json, clock)).excludePathPatterns(EXCLUDED).order(1);
    }

    @Configuration
    static class Beans {

        @Bean
        NanoClock nanoClock() {
            return System::nanoTime;
        }

        @Bean
        RouteOverrideCheck routeOverrideCheck(RateLimitProperties props) {
            return new RouteOverrideCheck(props);
        }

        @Bean
        RateLimitPolicy rateLimitPolicy(RateLimitProperties props) {
            return new RateLimitPolicy(props);
        }

        @Bean
        TokenBucketLimiter tokenBucketLimiter(NanoClock clock, RateLimitProperties props) {
            return new TokenBucketLimiter(clock, props.maxBuckets());
        }
    }
}
