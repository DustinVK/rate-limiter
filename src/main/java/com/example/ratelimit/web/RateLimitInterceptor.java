package com.example.ratelimit.web;

import com.example.ratelimit.BucketSpec;
import com.example.ratelimit.Decision;
import com.example.ratelimit.Decision.BucketResult;
import com.example.ratelimit.RateLimitPolicy;
import com.example.ratelimit.RateLimitPolicy.OrgLimits;
import com.example.ratelimit.TokenBucketLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks every request against its org's buckets before the controller runs.
 * Runs after {@link ApiKeyInterceptor}, so the org is already known.
 *
 * <p>Every response carries a {@code RateLimit} header (IETF
 * draft-ietf-httpapi-ratelimit-headers syntax) for the tightest bucket, so a
 * well-behaved sync job can slow down before it is ever rejected. Rejections
 * get 429 with {@code Retry-After} and a JSON body naming the binding limit.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimitPolicy policy;
    private final TokenBucketLimiter limiter;
    private final JsonMapper json;

    public RateLimitInterceptor(RateLimitPolicy policy, TokenBucketLimiter limiter, JsonMapper json) {
        this.policy = policy;
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String org = (String) request.getAttribute(ApiKeyInterceptor.ORG_ATTRIBUTE);
        String method = request.getMethod();
        // Matched route template, e.g. /items/{id}. Unmatched requests (404s)
        // have no controller and are charged to the org total only.
        String route = handler instanceof HandlerMethod
                ? (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
                : null;

        List<BucketSpec> specs = policy.bucketsFor(org, method, route);
        Decision decision = limiter.tryAcquire(specs);

        if (decision.outcome() == Decision.Outcome.LIMITER_FULL) {
            // Fail open: a full limiter must not turn into an outage for new
            // customers. The request goes through unlimited and untracked, and
            // this ERROR line is what pages someone. Orgs already tracked are
            // still limited, and depleted buckets are never evicted.
            log.error("limiter_full org={} method={} route={}", org, method, route);
            return true;
        }

        if (decision.allowed()) {
            BucketResult tightest = decision.tightest();
            response.setHeader("RateLimit", rateLimitHeader(tightest.spec().name(),
                    (long) Math.floor(tightest.remaining()), seconds(tightest.nanosUntilFull())));
            return true;
        }

        BucketResult binding = decision.binding();
        long retryAfter = seconds(binding.nanosUntilToken());
        OrgLimits limits = policy.limitsFor(org);
        log.warn("rate_limited org={} tier={} limit={} method={} route={} retry_after_s={} enforce={}",
                org, limits.tier(), binding.spec().name(), method, route, retryAfter, policy.enforcing());
        if (!policy.enforcing()) {
            return true; // shadow mode: logged above, request goes through
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "rate_limited");
        body.put("message", "Rate limit '" + binding.spec().name() + "' exceeded for org '" + org
                + "'. Retry after " + retryAfter + "s; add jitter to retries.");
        body.put("binding_limit", binding.spec().name());
        body.put("violated_limits", decision.exceeded().stream().map(r -> r.spec().name()).toList());
        body.put("org_id", org);
        body.put("tier", limits.tier());
        body.put("limit", Map.of("per_minute", binding.spec().limit().perMinute(), "burst", binding.spec().limit().burst()));
        body.put("endpoint", method + " " + Optional.ofNullable(route).orElse("(unmatched)"));
        body.put("retry_after_seconds", retryAfter);
        body.put("request_id", requestId(request));
        response.setHeader("RateLimit", rateLimitHeader(binding.spec().name(), 0, retryAfter));
        return reject(response, 429, retryAfter, body);
    }

    private boolean reject(HttpServletResponse response, int status, long retryAfter, Map<String, Object> body) throws Exception {
        response.setStatus(status);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
        return false;
    }

    private static String rateLimitHeader(String name, long remaining, long resetSeconds) {
        return "\"" + name + "\";r=" + remaining + ";t=" + resetSeconds;
    }

    /** Whole seconds, rounded up, never 0 for a positive wait. */
    static long seconds(long nanos) {
        return nanos <= 0 ? 0 : Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    }

    private static String requestId(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader("X-Request-Id")).orElseGet(() -> UUID.randomUUID().toString());
    }
}
