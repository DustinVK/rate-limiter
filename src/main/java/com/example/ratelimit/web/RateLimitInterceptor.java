package com.example.ratelimit.web;

import com.example.ratelimit.BucketSpec;
import com.example.ratelimit.Decision;
import com.example.ratelimit.Decision.BucketResult;
import com.example.ratelimit.NanoClock;
import com.example.ratelimit.RateLimitPolicy;
import com.example.ratelimit.TokenBucketLimiter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * The middleware: resolves the org from the API key, then checks the request
 * against the org's buckets before the controller runs.
 *
 * <p>Allowed responses carry a {@code RateLimit} header (IETF
 * draft-ietf-httpapi-ratelimit-headers syntax) for the tightest bucket, so a
 * well-behaved sync job can slow down before it is rejected. Rejections get
 * 429 with {@code Retry-After} and a JSON body naming the binding limit.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    static final String API_KEY_HEADER = "X-Api-Key";

    /** Client-supplied request IDs must look like this, so they can't forge log fields. */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /** limiter_full is logged at most this often; the rest are counted. */
    static final long LIMITER_FULL_LOG_INTERVAL_NANOS = 5_000_000_000L; // 5 s

    private final RateLimitPolicy policy;
    private final TokenBucketLimiter limiter;
    private final JsonMapper json;
    private final NanoClock clock;

    private final Object limiterFullLock = new Object();
    private long limiterFullLoggedAt;
    private long limiterFullSuppressed;

    public RateLimitInterceptor(RateLimitPolicy policy, TokenBucketLimiter limiter, JsonMapper json, NanoClock clock) {
        this.policy = policy;
        this.limiter = limiter;
        this.json = json;
        this.clock = clock;
        this.limiterFullLoggedAt = clock.nanoTime() - LIMITER_FULL_LOG_INTERVAL_NANOS; // first line logs at once
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS preflights never carry the key, and Spring answers them without a
        // controller. Async handlers are dispatched again on completion; checking
        // that second dispatch would charge the request twice.
        if (CorsUtils.isPreFlightRequest(request) || request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }

        // STAND-IN FOR REAL AUTH. The org comes from the API key, never from a
        // client-supplied ID: a client that chose its org could rotate it for
        // fresh buckets. Invalid keys get 401 and never create limiter state.
        String org = policy.orgForApiKey(request.getHeader(API_KEY_HEADER)).orElse(null);
        if (org == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Map.of(
                    "error", "unauthorized",
                    "message", "Missing or unknown " + API_KEY_HEADER + " header."));
            return false;
        }

        String method = request.getMethod();
        // Matched route template, e.g. /items/{id}; null for 404s, which have no
        // controller and are charged to the org total only.
        String route = handler instanceof HandlerMethod
                ? (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
                : null;

        List<BucketSpec> specs = policy.bucketsFor(org, method, route);
        Decision decision = limiter.tryAcquire(specs);

        if (decision.outcome() == Decision.Outcome.ALLOWED_UNTRACKED) {
            logLimiterFull(org, method, route, request); // fail open, and page someone
        }

        if (decision.allowed()) {
            // Shadow mode sends no header: clients must not slow down during a trial.
            if (policy.enforcing()) {
                decision.tightest().ifPresent(t -> response.setHeader("RateLimit", rateLimitHeader(t.spec().name(),
                        (long) Math.floor(t.remaining()), seconds(t.nanosUntilFull()))));
            }
            return true;
        }

        BucketResult binding = decision.binding();
        long retryAfter = seconds(binding.nanosUntilToken());
        String tier = policy.limitsFor(org).tier();
        // Same ID in the log line and in the 429 body, so support can find one from the other.
        String requestId = requestId(request);
        log.warn("rate_limited org={} tier={} limit={} method={} route={} retry_after_s={} enforce={} request_id={}",
                org, tier, binding.spec().name(), method, route, retryAfter, policy.enforcing(), requestId);
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
        body.put("tier", tier);
        body.put("limit", Map.of("per_minute", binding.spec().limit().perMinute(), "burst", binding.spec().limit().burst()));
        body.put("endpoint", method + " " + Objects.requireNonNullElse(route, "(unmatched)"));
        body.put("retry_after_seconds", retryAfter);
        body.put("request_id", requestId);

        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("RateLimit", rateLimitHeader(binding.spec().name(), 0, retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
        return false;
    }

    /**
     * One ERROR line per interval, carrying how many were skipped since the last
     * one. During a flood of new keys this would otherwise be thousands of lines,
     * and pages, a second.
     */
    private void logLimiterFull(String org, String method, String route, HttpServletRequest request) {
        long now = clock.nanoTime();
        long suppressed;
        synchronized (limiterFullLock) {
            if (now - limiterFullLoggedAt < LIMITER_FULL_LOG_INTERVAL_NANOS) {
                limiterFullSuppressed++;
                return;
            }
            suppressed = limiterFullSuppressed;
            limiterFullSuppressed = 0;
            limiterFullLoggedAt = now;
        }
        log.error("limiter_full org={} method={} route={} request_id={} suppressed_since_last={}",
                org, method, route, requestId(request), suppressed);
    }

    private static String rateLimitHeader(String name, long remaining, long resetSeconds) {
        return "\"" + name + "\";r=" + remaining + ";t=" + resetSeconds;
    }

    /** Whole seconds, rounded up. */
    static long seconds(long nanos) {
        return nanos <= 0 ? 0 : Math.ceilDiv(nanos, 1_000_000_000L);
    }

    /** The client's X-Request-Id if it is safe to log as-is, else a fresh UUID. */
    private static String requestId(HttpServletRequest request) {
        String id = request.getHeader("X-Request-Id");
        return id != null && SAFE_REQUEST_ID.matcher(id).matches() ? id : UUID.randomUUID().toString();
    }
}
