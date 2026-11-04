# Rate-limiting middleware: code artifact

A proof-of-concept rate limiter for a Spring Boot 4 / Java 21 API: one Spring MVC interceptor checks every request against in-memory token buckets, per org and per endpoint, before any controller runs.

- **Commit:** `93e1f49`
- **Run the tests:** `mvn test` (39 tests, fake clock, no sleeps)
- **Run the app:** `mvn spring-boot:run` (serves on :8080, reads `./ratelimit.yml`)
- **Restart and replica demo:** `./scripts/demo.sh`

The README (uploaded separately) covers architecture, trade-offs, failure modes and the AI usage log.

## Contents

1. **Proof it works:** live curl run against the sample API
2. **Configuration schema:** `ratelimit.yml`, `application.yml`, `RateLimitProperties`
3. **Middleware:** `RateLimitInterceptor`, `RateLimitPolicy`, `TokenBucketLimiter` and its small types
4. **Integration example:** `RateLimitWebConfig`, `RouteOverrideCheck`, `DemoController` (sample routes with no rate-limit code), application class, `pom.xml`
5. **Selected tests:** limiter unit tests, failure-mode tests, memory-cap tests, end-to-end MockMvc tests

## 1. Proof it works

Org `globex` is on the standard tier: its write bucket for `POST /items` holds 5 tokens and refills at 20 per minute (one every 3 seconds).

```console
$ for i in $(seq 5); do curl -s -o /dev/null -w '%{http_code} ' -X POST -H 'X-Api-Key: key-globex' localhost:8080/items; done; echo
201 201 201 201 201

$ curl -i -X POST -H 'X-Api-Key: key-globex' localhost:8080/items
HTTP/1.1 429
Retry-After: 3
Cache-Control: no-store
RateLimit: "endpoint-write";r=0;t=3
Content-Type: application/json
Content-Length: 373

{"error":"rate_limited","message":"Rate limit 'endpoint-write' exceeded for org 'globex'. Retry after 3s; add jitter to retries.","binding_limit":"endpoint-write","violated_limits":["endpoint-write"],"org_id":"globex","tier":"standard","limit":{"per_minute":20,"burst":5},"endpoint":"POST /items","retry_after_seconds":3,"request_id":"c3b40f9d-18f9-4da4-8003-0ade1eb36a53"}

$ curl -i -H 'X-Api-Key: key-globex' localhost:8080/items
HTTP/1.1 200
RateLimit: "endpoint-read";r=14;t=1
Content-Type: application/json
Content-Length: 12

{"items":[]}
```

The sixth write is rejected by the per-endpoint write limit, while a read from the same org still succeeds.

### Restart and replica demo (`scripts/demo.sh`)

```console
DEMO_OUTPUT_PLACEHOLDER: run ./scripts/demo.sh and paste its output here
```

## 2. Configuration schema

### `ratelimit.yml`

```yaml
# Rate limits. Edit this file and restart the service; no code change needed.
# A restart resets every counter (see README, "Known failure modes").
# Point the service at a different file with RATELIMIT_FILE=/path/to/file.yml.
#
# Units: per-minute is the sustained refill rate; burst is how many requests
# can arrive back-to-back after a quiet period (the bucket size).
ratelimit:
  # false = shadow mode: log every request that WOULD be rejected, block nothing.
  # Turn a new or changed limit on in shadow mode first and watch the logs.
  enforce: true

  # Hard cap on buckets held in memory, about 160 bytes each (measured), so
  # 100k is roughly 16 MB. Live buckets = active orgs x endpoints each touched
  # in the last refill period. At the cap, fully refilled buckets are dropped
  # (free); depleted ones never are. If no room can be made, requests that need
  # a NEW bucket go through unlimited and a limiter_full ERROR is logged.
  max-buckets: 100000

  default-tier: standard

  # Every request is checked against two buckets, and must pass both:
  #   total - all of this org's requests, across every endpoint
  #   read / write - this org's requests to ONE endpoint (method + route template)
  # GET/HEAD/OPTIONS are reads; everything else is a write.
  tiers:
    standard:
      total: { per-minute: 100, burst: 20 }
      read:  { per-minute: 60,  burst: 15 }   # per endpoint, so one hot read can't use the whole total
      write: { per-minute: 20,  burst: 5 }
    premium:
      total: { per-minute: 500, burst: 100 }
      read:  { per-minute: 300, burst: 75 }
      write: { per-minute: 100, burst: 20 }

  # Per-org settings. Any of total/read/write here replaces the tier's value.
  orgs:
    acme:   { tier: premium }
    globex: { tier: standard }
    initech:
      tier: standard
      write: { per-minute: 40, burst: 10 }

  # Endpoint-specific limits, for every org, replacing the read/write default
  # for that one endpoint. Path is the route TEMPLATE as declared in the code.
  route-overrides:
    - { method: POST, path: /exports, per-minute: 5, burst: 1 }

  # STAND-IN FOR AUTH. Real API keys never live in a config file; in production
  # the org comes from the authentication layer. Several keys can share an org,
  # and they share its limits.
  api-keys:
    key-acme-1: acme
    key-acme-2: acme
    key-globex: globex
    key-initech: initech
```

### `src/main/resources/application.yml`

```yaml
spring:
  application:
    name: rate-limiter
  config:
    # Not optional on purpose: no limits file means the service refuses to start.
    # RATELIMIT_FILE takes a full location, e.g. file:/etc/api/ratelimit.yml.
    import: ${RATELIMIT_FILE:file:./ratelimit.yml}
```

### `src/main/java/com/example/ratelimit/RateLimitProperties.java`

```java
package com.example.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code ratelimit:} section of ratelimit.yml. Bound and validated at
 * startup; any violation stops the service from starting, so a typo can't
 * silently become "no limit". Cross-references (unknown tier names and so on)
 * are checked in {@link RateLimitPolicy}.
 */
@Validated
@ConfigurationProperties(prefix = "ratelimit", ignoreUnknownFields = false)
public record RateLimitProperties(
        @DefaultValue("true") boolean enforce,
        @DefaultValue("100000") @Positive int maxBuckets,
        @NotBlank String defaultTier,
        @NotEmpty Map<String, @Valid TierLimits> tiers,
        Map<String, @Valid OrgSettings> orgs,
        List<@Valid RouteOverride> routeOverrides,
        @NotEmpty Map<String, String> apiKeys) {

    public RateLimitProperties {
        orgs = orgs == null ? Map.of() : orgs;
        routeOverrides = routeOverrides == null ? List.of() : routeOverrides;
    }

    public record TierLimits(@NotNull @Valid Limit total, @NotNull @Valid Limit read, @NotNull @Valid Limit write) {
    }

    /** Org settings; a null limit means "use the tier's value". */
    public record OrgSettings(String tier, @Valid Limit total, @Valid Limit read, @Valid Limit write) {
    }

    public record RouteOverride(@NotBlank String method, @NotBlank String path,
                                @Positive int perMinute, @Positive int burst) {
        public Limit limit() {
            return new Limit(perMinute, burst);
        }
    }
}
```

## 3. Middleware

### `src/main/java/com/example/ratelimit/web/RateLimitInterceptor.java`

```java
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
```

### `src/main/java/com/example/ratelimit/RateLimitPolicy.java`

```java
package com.example.ratelimit;

import com.example.ratelimit.RateLimitProperties.OrgSettings;
import com.example.ratelimit.RateLimitProperties.RouteOverride;
import com.example.ratelimit.RateLimitProperties.TierLimits;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the config into the list of buckets one request must pass:
 * <ol>
 *   <li>{@code org-total}: every request from the org, any endpoint;</li>
 *   <li>{@code endpoint-read}/{@code endpoint-write} (or {@code route:<METHOD path>}
 *       for a route override): the org's requests to this one endpoint.</li>
 * </ol>
 * Endpoints are keyed by route template ({@code /items/{id}}), never by raw
 * path ({@code /items/123}); raw paths would let any client mint unlimited
 * buckets, which is both a memory leak and a way around the limit.
 */
public final class RateLimitPolicy {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** Resolved limits for one org. */
    public record OrgLimits(String orgId, String tier, Limit total, Limit read, Limit write) {
    }

    private final RateLimitProperties props;
    private final Map<String, Limit> routeOverrides = new HashMap<>();

    public RateLimitPolicy(RateLimitProperties props) {
        this.props = props;
        validate();
        for (RouteOverride r : props.routeOverrides()) {
            routeOverrides.put(endpoint(r.method(), r.path()), r.limit());
        }
    }

    private void validate() {
        if (!props.tiers().containsKey(props.defaultTier())) {
            throw new IllegalStateException("ratelimit.default-tier '" + props.defaultTier() + "' is not in ratelimit.tiers");
        }
        props.orgs().forEach((org, s) -> {
            if (s.tier() != null && !props.tiers().containsKey(s.tier())) {
                throw new IllegalStateException("ratelimit.orgs." + org + ".tier '" + s.tier() + "' is not in ratelimit.tiers");
            }
        });
    }

    public boolean enforcing() {
        return props.enforce();
    }

    /** The org an API key belongs to. Stand-in for a real authentication layer. */
    public Optional<String> orgForApiKey(String apiKey) {
        return Optional.ofNullable(apiKey).map(props.apiKeys()::get);
    }

    public OrgLimits limitsFor(String orgId) {
        OrgSettings s = props.orgs().get(orgId);
        String tierName = s != null && s.tier() != null ? s.tier() : props.defaultTier();
        TierLimits tier = props.tiers().get(tierName);
        if (s == null) {
            return new OrgLimits(orgId, tierName, tier.total(), tier.read(), tier.write());
        }
        return new OrgLimits(orgId, tierName,
                s.total() != null ? s.total() : tier.total(),
                s.read() != null ? s.read() : tier.read(),
                s.write() != null ? s.write() : tier.write());
    }

    /**
     * @param routeTemplate the matched route template, or null when no controller
     *                      matched (404s still count toward the org total)
     */
    public List<BucketSpec> bucketsFor(String orgId, String method, String routeTemplate) {
        OrgLimits org = limitsFor(orgId);
        BucketSpec total = new BucketSpec("org:" + orgId + ":total", "org-total", org.total());
        if (routeTemplate == null) {
            return List.of(total);
        }
        String endpoint = endpoint(method, routeTemplate);
        Limit override = routeOverrides.get(endpoint);
        String key = "org:" + orgId + ":" + endpoint;
        if (override != null) {
            return List.of(total, new BucketSpec(key, "route:" + endpoint, override));
        }
        return isRead(method)
                ? List.of(total, new BucketSpec(key, "endpoint-read", org.read()))
                : List.of(total, new BucketSpec(key, "endpoint-write", org.write()));
    }

    /** Anything that is not a known read is a write: unknown methods get the stricter limit. */
    static boolean isRead(String method) {
        return READ_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    /** HEAD (and OPTIONS) are keyed as GET, so they share its read bucket. */
    public static String endpoint(String method, String routeTemplate) {
        String m = isRead(method) ? "GET" : method.toUpperCase(Locale.ROOT);
        return m + " " + routeTemplate;
    }
}
```

### `src/main/java/com/example/ratelimit/TokenBucketLimiter.java`

```java
package com.example.ratelimit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory token buckets for one process: lost on restart, not shared between
 * replicas (README, B1).
 * <ul>
 *   <li>Tokens are taken from all of a request's buckets or from none, so a
 *       rejected request never spends any allowance.</li>
 *   <li>Refill is computed lazily from monotonic time; there are no timers.</li>
 *   <li>One lock guards everything; the work under it is a few map operations,
 *       plus a sweep at most every 100 ms.</li>
 *   <li>Only fully refilled buckets are evicted: a full bucket behaves exactly
 *       like a missing one, while dropping a depleted one would reset that org.</li>
 * </ul>
 */
public final class TokenBucketLimiter {

    /**
     * Sweep for fully refilled buckets every SWEEP_EVERY checks, or when the cap
     * is reached, but never more often than MIN_SWEEP_INTERVAL: a sweep scans
     * every bucket under the lock, so sweeping per request would stall every org.
     */
    static final int SWEEP_EVERY = 1024;
    static final long MIN_SWEEP_INTERVAL_NANOS = 100_000_000L; // 100 ms

    private static final class Bucket {
        final Limit limit;
        double tokens;
        long updatedAt;

        Bucket(Limit limit, double tokens, long updatedAt) {
            this.limit = limit;
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }

        double tokensAt(long now) {
            double refilled = tokens + (now - updatedAt) * limit.tokensPerNano();
            return Math.min(limit.burst(), refilled);
        }
    }

    private final Map<String, Bucket> buckets = new HashMap<>();
    private final NanoClock clock;
    private final int maxBuckets;
    private int checksSinceSweep;
    private long lastSweepAt;
    private long sweeps;

    public TokenBucketLimiter(NanoClock clock, int maxBuckets) {
        this.clock = clock;
        this.maxBuckets = maxBuckets;
        this.lastSweepAt = clock.nanoTime() - MIN_SWEEP_INTERVAL_NANOS; // first sweep may run at once
    }

    public synchronized Decision tryAcquire(List<BucketSpec> specs) {
        long now = clock.nanoTime();
        long missing = specs.stream().filter(s -> !buckets.containsKey(s.key())).count();
        if (++checksSinceSweep >= SWEEP_EVERY || buckets.size() + missing > maxBuckets) {
            sweepIfDue(now);
        }

        // Phase 1: evaluate every bucket without changing anything.
        // A missing bucket is full by definition, so it never rejects.
        double[] available = new double[specs.size()];
        boolean allowed = true;
        for (int i = 0; i < specs.size(); i++) {
            Bucket b = buckets.get(specs.get(i).key());
            available[i] = b == null ? specs.get(i).limit().burst() : b.tokensAt(now);
            if (available[i] < 1) {
                allowed = false;
            }
        }

        // Phase 2: take one token from every bucket, or from none. At the cap,
        // missing buckets are created in order (org total first) while room
        // remains; the rest go untracked and are left out of the results.
        // Existing buckets were enforced in phase 1 either way.
        List<Decision.BucketResult> results = new ArrayList<>(specs.size());
        boolean untracked = false;
        for (int i = 0; i < specs.size(); i++) {
            BucketSpec spec = specs.get(i);
            Limit limit = spec.limit();
            double remaining = available[i];
            if (allowed) {
                remaining -= 1;
                Bucket b = buckets.get(spec.key());
                if (b != null) {
                    b.tokens = remaining;
                    b.updatedAt = now;
                } else if (buckets.size() < maxBuckets) {
                    buckets.put(spec.key(), new Bucket(limit, remaining, now));
                } else {
                    untracked = true;
                    continue;
                }
            }
            long untilToken = allowed ? 0 : limit.nanosUntilOneToken(available[i]);
            results.add(new Decision.BucketResult(spec, remaining, untilToken, limit.nanosUntilFull(remaining)));
        }
        Decision.Outcome outcome = !allowed ? Decision.Outcome.RATE_LIMITED
                : untracked ? Decision.Outcome.ALLOWED_UNTRACKED
                : Decision.Outcome.ALLOWED;
        return new Decision(outcome, results);
    }

    /** Drop buckets that have fully refilled. O(buckets) while holding the lock. */
    private void sweepIfDue(long now) {
        if (now - lastSweepAt < MIN_SWEEP_INTERVAL_NANOS) {
            return;
        }
        checksSinceSweep = 0;
        lastSweepAt = now;
        sweeps++;
        buckets.values().removeIf(b -> b.tokensAt(now) >= b.limit.burst());
    }

    public synchronized int size() {
        return buckets.size();
    }

    synchronized long sweeps() {
        return sweeps;
    }
}
```

### `src/main/java/com/example/ratelimit/Decision.java`

```java
package com.example.ratelimit;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** The outcome of checking one request against all of its buckets. */
public record Decision(Outcome outcome, List<BucketResult> results) {

    public enum Outcome {
        ALLOWED,
        /**
         * Allowed, but the limiter is at max-buckets and could not store some of
         * the request's buckets. The buckets that exist were still checked and
         * charged; the missing ones are not tracked and not in the results.
         */
        ALLOWED_UNTRACKED,
        RATE_LIMITED
    }

    /**
     * @param remaining       tokens left after this request (unchanged if it was rejected)
     * @param nanosUntilToken 0 if this bucket allowed the request, else the wait for one token
     * @param nanosUntilFull  wait until the bucket is completely refilled
     */
    public record BucketResult(BucketSpec spec, double remaining, long nanosUntilToken, long nanosUntilFull) {
        public boolean exceeded() {
            return nanosUntilToken > 0;
        }
    }

    public boolean allowed() {
        return outcome != Outcome.RATE_LIMITED;
    }

    /** Buckets that rejected the request. */
    public List<BucketResult> exceeded() {
        return results.stream().filter(BucketResult::exceeded).toList();
    }

    /**
     * The limit that actually decides when the client may retry: of the buckets
     * that rejected the request, the one with the longest wait. Retrying when
     * the first-tripped bucket refills would just hit the next one.
     */
    public BucketResult binding() {
        return exceeded().stream().max(Comparator.comparingLong(BucketResult::nanosUntilToken)).orElseThrow();
    }

    /**
     * The bucket with the fewest tokens left, reported in RateLimit headers on
     * success. Empty when none of the request's buckets could be tracked.
     */
    public Optional<BucketResult> tightest() {
        return results.stream().min(Comparator.comparingDouble(BucketResult::remaining));
    }
}
```

### `src/main/java/com/example/ratelimit/BucketSpec.java`

```java
package com.example.ratelimit;

/**
 * One bucket a request must pass.
 *
 * @param key   identity of the bucket in memory, e.g. {@code org:acme:POST /items}
 * @param name  the limit's public name, returned to clients, e.g. {@code endpoint-write}
 * @param limit rate and burst for this bucket
 */
public record BucketSpec(String key, String name, Limit limit) {
}
```

### `src/main/java/com/example/ratelimit/Limit.java`

```java
package com.example.ratelimit;

import jakarta.validation.constraints.Positive;

/**
 * A token bucket: holds up to {@code burst} tokens and refills at
 * {@code perMinute} tokens per minute. Each request spends one token.
 */
public record Limit(@Positive int perMinute, @Positive int burst) {

    private static final double NANOS_PER_MINUTE = 60e9;

    double tokensPerNano() {
        return perMinute / NANOS_PER_MINUTE;
    }

    /** Nanoseconds until a bucket holding {@code tokens} has at least one token. */
    long nanosUntilOneToken(double tokens) {
        return tokens >= 1 ? 0 : (long) Math.ceil((1 - tokens) / tokensPerNano());
    }

    /** Nanoseconds until a bucket holding {@code tokens} is full again. */
    long nanosUntilFull(double tokens) {
        return tokens >= burst ? 0 : (long) Math.ceil((burst - tokens) / tokensPerNano());
    }
}
```

### `src/main/java/com/example/ratelimit/NanoClock.java`

```java
package com.example.ratelimit;

/**
 * Monotonic time source. Production uses System::nanoTime, which never jumps
 * when NTP adjusts the wall clock; tests inject a clock they can advance.
 */
@FunctionalInterface
public interface NanoClock {
    long nanoTime();
}
```

## 4. Integration example

### `src/main/java/com/example/ratelimit/web/RateLimitWebConfig.java`

```java
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
```

### `src/main/java/com/example/ratelimit/web/RouteOverrideCheck.java`

```java
package com.example.ratelimit.web;

import com.example.ratelimit.RateLimitPolicy;
import com.example.ratelimit.RateLimitProperties;
import com.example.ratelimit.RateLimitProperties.RouteOverride;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Fails startup if a route override names an endpoint no controller serves,
 * e.g. {@code /export} for {@code /exports} or {@code PSOT}. Such an override
 * would otherwise never match, and the endpoint would silently keep its default.
 */
class RouteOverrideCheck implements ApplicationListener<ContextRefreshedEvent> {

    private final RateLimitProperties props;

    RouteOverrideCheck(RateLimitProperties props) {
        this.props = props;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        RequestMappingHandlerMapping mapping = event.getApplicationContext()
                .getBeanProvider(RequestMappingHandlerMapping.class).getIfUnique();
        if (mapping == null) {
            return; // no Spring MVC in this context (config-only tests)
        }
        Set<String> served = new HashSet<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (RequestMethod m : methods.isEmpty() ? EnumSet.allOf(RequestMethod.class) : methods) {
                info.getPatternValues().forEach(path -> served.add(RateLimitPolicy.endpoint(m.name(), path)));
            }
        }
        for (RouteOverride r : props.routeOverrides()) {
            String endpoint = RateLimitPolicy.endpoint(r.method(), r.path());
            if (!served.contains(endpoint)) {
                throw new IllegalStateException("ratelimit.route-overrides: no controller serves '" + endpoint + "'");
            }
        }
    }
}
```

### `src/main/java/com/example/demo/DemoController.java`

```java
package com.example.demo;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-in API. Note there is no rate-limiting code here: the interceptor
 * covers every route.
 */
@RestController
public class DemoController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/items")
    public Map<String, Object> listItems() {
        return Map.of("items", java.util.List.of());
    }

    @GetMapping("/items/{id}")
    public Map<String, String> getItem(@PathVariable String id) {
        return Map.of("id", id);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> createItem() {
        return Map.of("status", "created");
    }

    @PutMapping("/items/{id}")
    public Map<String, String> updateItem(@PathVariable String id) {
        return Map.of("id", id, "status", "updated");
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable String id) {
    }

    @PostMapping("/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> startExport() {
        return Map.of("status", "export started");
    }
}
```

### `src/main/java/com/example/RateLimiterApplication.java`

```java
package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RateLimiterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }
}
```

### `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.2</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>rate-limiter</artifactId>
    <version>0.1.0</version>
    <name>rate-limiter</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>rate-limiter</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

## 5. Selected tests

### `src/test/java/com/example/ratelimit/TokenBucketLimiterTest.java`

```java
package com.example.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TokenBucketLimiterTest {

    private final FakeClock clock = new FakeClock();
    private final TokenBucketLimiter limiter = new TokenBucketLimiter(clock, 10_000);

    private static BucketSpec bucket(String key, int perMinute, int burst) {
        return new BucketSpec(key, key, new Limit(perMinute, burst));
    }

    @Test
    void allowsBurstThenRejectsWithExactWait() {
        List<BucketSpec> specs = List.of(bucket("a", 60, 3));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(specs).allowed()).isTrue();
        }
        Decision d = limiter.tryAcquire(specs);
        assertThat(d.outcome()).isEqualTo(Decision.Outcome.RATE_LIMITED);
        // 60/min = one token per second.
        assertThat(d.binding().nanosUntilToken()).isEqualTo(Duration.ofSeconds(1).toNanos());
    }

    @Test
    void refillsLazilyAndFractionally() {
        List<BucketSpec> specs = List.of(bucket("a", 60, 1));
        assertThat(limiter.tryAcquire(specs).allowed()).isTrue();
        clock.advance(Duration.ofMillis(500));
        Decision d = limiter.tryAcquire(specs);
        assertThat(d.allowed()).isFalse();
        assertThat(d.binding().nanosUntilToken()).isEqualTo(Duration.ofMillis(500).toNanos());
        clock.advance(Duration.ofMillis(500));
        assertThat(limiter.tryAcquire(specs).allowed()).isTrue();
    }

    @Test
    void rejectedRequestSpendsNoTokensFromAnyBucket() {
        BucketSpec total = bucket("total", 60, 10);
        BucketSpec write = bucket("write", 60, 1);
        assertThat(limiter.tryAcquire(List.of(total, write)).allowed()).isTrue();

        // The write bucket is empty, so this is rejected...
        Decision rejected = limiter.tryAcquire(List.of(total, write));
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.exceeded()).extracting(r -> r.spec().name()).containsExactly("write");

        // ...and it took nothing from total, which still holds 9 (10 minus the
        // first request). This next request spends one, leaving 8; had the
        // rejected request been charged too, 7 would be left.
        Decision read = limiter.tryAcquire(List.of(total));
        assertThat(read.results().get(0).remaining()).isEqualTo(8.0);
    }

    @Test
    void bindingLimitIsTheLongestWait() {
        BucketSpec fast = bucket("fast", 600, 1); // refills in 0.1s
        BucketSpec slow = bucket("slow", 6, 1);   // refills in 10s
        limiter.tryAcquire(List.of(fast, slow));
        Decision d = limiter.tryAcquire(List.of(fast, slow));
        assertThat(d.exceeded()).hasSize(2);
        assertThat(d.binding().spec().name()).isEqualTo("slow");
        assertThat(d.binding().nanosUntilToken()).isEqualTo(Duration.ofSeconds(10).toNanos());
    }

    /** Last week's incident: a sync job sending steadily above its limit for hours. */
    @Test
    void sustainedOverloadSettlesAtTheConfiguredRate() {
        List<BucketSpec> specs = List.of(bucket("sync-job", 60, 5));
        int allowed = 0;
        // Twice the allowed rate (one request every 0.5s) for 10 minutes.
        for (int i = 0; i < 1200; i++) {
            if (limiter.tryAcquire(specs).allowed()) {
                allowed++;
            }
            clock.advance(Duration.ofMillis(500));
        }
        // 600 from refill over 10 minutes, plus the initial burst of 5.
        assertThat(allowed).isBetween(600, 605);
    }

    @Test
    void concurrentRequestsNeverOverspend() throws Exception {
        List<BucketSpec> specs = List.of(bucket("a", 60, 10));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<Decision.Outcome>> outcomes = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            outcomes.add(pool.submit(() -> {
                start.await();
                return limiter.tryAcquire(specs).outcome();
            }));
        }
        start.countDown();
        int allowed = 0;
        for (Future<Decision.Outcome> f : outcomes) {
            // get() rethrows anything a worker threw, so errors can't hide.
            if (f.get(10, TimeUnit.SECONDS) == Decision.Outcome.ALLOWED) {
                allowed++;
            }
        }
        pool.shutdown();
        assertThat(allowed).isEqualTo(10); // clock is frozen: burst only; the other 490 are rate-limited
    }
}
```

### `src/test/java/com/example/ratelimit/FailureModesTest.java`

```java
package com.example.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Tests that PASS BECAUSE a limitation exists. They document what in-memory
 * state cannot do. If one starts failing, the limitation has changed (e.g.
 * state moved to Redis) and the README must change with it.
 */
class FailureModesTest {

    private final FakeClock clock = new FakeClock();
    private final List<BucketSpec> org = List.of(new BucketSpec("org:noisy:total", "org-total", new Limit(60, 5)));

    private static int drain(TokenBucketLimiter limiter, List<BucketSpec> specs) {
        int allowed = 0;
        while (limiter.tryAcquire(specs).allowed()) {
            allowed++;
        }
        return allowed;
    }

    @Test
    void KNOWN_LIMITATION_restartResetsEveryLimit() {
        TokenBucketLimiter beforeDeploy = new TokenBucketLimiter(clock, 100);
        assertThat(drain(beforeDeploy, org)).isEqualTo(5);
        assertThat(beforeDeploy.tryAcquire(org).allowed()).isFalse();

        // A restart or deploy is a new process: a new, empty limiter.
        TokenBucketLimiter afterDeploy = new TokenBucketLimiter(clock, 100);
        assertThat(drain(afterDeploy, org)).as("noisy org gets a full burst again").isEqualTo(5);
    }

    @Test
    void KNOWN_LIMITATION_replicasMultiplyTheLimit() {
        List<TokenBucketLimiter> replicas = IntStream.range(0, 3)
                .mapToObj(i -> new TokenBucketLimiter(clock, 100)).toList();

        // Round-robin load balancer: request i goes to replica i % 3.
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (replicas.get(i % 3).tryAcquire(org).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).as("configured burst is 5; 3 replicas let through 3x").isEqualTo(15);
    }

    @Test
    void memoryIsCappedAndDepletedBucketsAreNeverEvicted() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(clock, 3);
        Limit limit = new Limit(60, 1);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(List.of(new BucketSpec("org:" + i, "org-total", limit)));
        }
        List<BucketSpec> noisy = List.of(new BucketSpec("org:0", "org-total", limit));
        List<BucketSpec> newcomer = List.of(new BucketSpec("org:new", "org-total", limit));

        // At the cap with every bucket depleted: the new org can't be tracked
        // (the middleware lets it through and logs limiter_full), rather than
        // evicting a depleted bucket and handing that org a reset.
        assertThat(limiter.tryAcquire(newcomer).outcome()).isEqualTo(Decision.Outcome.ALLOWED_UNTRACKED);
        assertThat(limiter.tryAcquire(noisy).outcome()).as("still limited, not reset")
                .isEqualTo(Decision.Outcome.RATE_LIMITED);
        assertThat(limiter.size()).isEqualTo(3);

        // Once buckets have fully refilled they are dropped, and room appears.
        clock.advance(Duration.ofSeconds(2));
        assertThat(limiter.tryAcquire(newcomer).allowed()).isTrue();
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    void atTheCapExistingBucketsAreStillEnforcedForNewEndpoints() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(clock, 1);
        BucketSpec total = new BucketSpec("org:noisy:total", "org-total", new Limit(60, 2));
        BucketSpec brandNewEndpoint = new BucketSpec("org:noisy:GET /reports", "endpoint-read", new Limit(60, 5));
        limiter.tryAcquire(List.of(total));
        limiter.tryAcquire(List.of(total));

        // The cap is full (just the total) and the total is empty. Calling an
        // endpoint with no bucket yet must not slip past the empty total.
        Decision d = limiter.tryAcquire(List.of(total, brandNewEndpoint));
        assertThat(d.outcome()).isEqualTo(Decision.Outcome.RATE_LIMITED);
        assertThat(d.binding().spec().name()).isEqualTo("org-total");
    }

    @Test
    void atTheCapARequestIsChargedToItsExistingBucketsAndTheRestGoUntracked() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(clock, 1);
        BucketSpec total = new BucketSpec("org:noisy:total", "org-total", new Limit(60, 3));
        BucketSpec brandNewEndpoint = new BucketSpec("org:noisy:GET /reports", "endpoint-read", new Limit(60, 5));
        limiter.tryAcquire(List.of(total));

        Decision d = limiter.tryAcquire(List.of(total, brandNewEndpoint));
        assertThat(d.outcome()).isEqualTo(Decision.Outcome.ALLOWED_UNTRACKED);
        assertThat(limiter.size()).as("no room was made, so nothing new is stored").isEqualTo(1);

        // The total was still charged: 3 - 2 = 1 left, then empty.
        assertThat(limiter.tryAcquire(List.of(total)).allowed()).isTrue();
        assertThat(limiter.tryAcquire(List.of(total)).outcome()).isEqualTo(Decision.Outcome.RATE_LIMITED);
    }

    @Test
    void atTheCapMissingBucketsAreCreatedInOrderWhileRoomRemains() {
        // Room for one bucket; every request needs two (org total + endpoint).
        TokenBucketLimiter limiter = new TokenBucketLimiter(clock, 1);
        BucketSpec total = new BucketSpec("org:new:total", "org-total", new Limit(60, 3));
        BucketSpec endpoint = new BucketSpec("org:new:GET /items", "endpoint-read", new Limit(60, 5));

        // The org total takes the one slot and is enforced; the endpoint goes untracked.
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(List.of(total, endpoint)).outcome()).isEqualTo(Decision.Outcome.ALLOWED_UNTRACKED);
        }
        assertThat(limiter.size()).isEqualTo(1);
        assertThat(limiter.tryAcquire(List.of(total, endpoint)).outcome()).isEqualTo(Decision.Outcome.RATE_LIMITED);
    }

    @Test
    void aKeyFloodAtTheCapSweepsAtMostOncePer100ms() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(clock, 100);
        Limit limit = new Limit(60, 10);
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire(List.of(new BucketSpec("org:" + i, "org-total", limit)));
        }
        long before = limiter.sweeps();

        // 10,000 new keys inside one second of fake time: without the
        // throttle, each would scan all 100 buckets under the lock.
        for (int i = 0; i < 10_000; i++) {
            limiter.tryAcquire(List.of(new BucketSpec("flood:" + i, "org-total", limit)));
            clock.advance(Duration.ofNanos(100_000)); // 0.1 ms
        }
        assertThat(limiter.sweeps() - before).isBetween(9L, 11L); // ~1s / 100ms
    }

    @Test
    void KNOWN_LIMITATION_aKeyFloodFillsTheCapAndNewKeysGoUntracked() {
        TokenBucketLimiter limiter = new TokenBucketLimiter(clock, 1_000);
        Limit limit = new Limit(60, 10);
        long untracked = IntStream.range(0, 100_000)
                .mapToObj(i -> limiter.tryAcquire(List.of(new BucketSpec("anon:" + i, "anon", limit))))
                .filter(d -> d.outcome() == Decision.Outcome.ALLOWED_UNTRACKED)
                .count();
        // Memory is bounded...
        assertThat(limiter.size()).isEqualTo(1_000);
        // ...but new keys can't be tracked until buckets refill, so the
        // middleware lets them through unlimited (and logs limiter_full).
        // This is why keys come from authenticated orgs and route templates,
        // never from raw paths or client-chosen IDs.
        assertThat(untracked).isEqualTo(99_000);
    }
}
```

### `src/test/java/com/example/ratelimit/web/LimiterFullTest.java`

```java
package com.example.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ratelimit.FakeClock;
import com.example.ratelimit.FakeClockConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** A full limiter must not become an outage for orgs it can't track. */
@SpringBootTest(properties = {"RATELIMIT_FILE=classpath:ratelimit-test.yml", "ratelimit.max-buckets=2"})
@AutoConfigureMockMvc
@Import(FakeClockConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LimiterFullTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeClock clock;

    /**
     * globex fills the cap (max-buckets=2: org-total + GET /items) and empties its
     * total (burst 10): 5 reads empty GET /items, 5 unmatched paths hit only the total.
     */
    private void exhaustGlobexTotal() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/items").header("X-Api-Key", "key-globex")).andExpect(status().isOk());
        }
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/no-such-route").header("X-Api-Key", "key-globex")).andExpect(status().isNotFound());
        }
    }

    @Test
    void newOrgIsLetThroughAndLoggedWhenTheCapIsFull(CapturedOutput output) throws Exception {
        // globex fills the cap: one request creates org-total + GET /items.
        mvc.perform(get("/items").header("X-Api-Key", "key-globex")).andExpect(status().isOk());

        // acme needs two new buckets and there is no room, so it goes through
        // untracked: more requests than any of its limits would allow.
        for (int i = 0; i < 200; i++) {
            mvc.perform(get("/items").header("X-Api-Key", "key-acme-1")).andExpect(status().isOk());
        }
        assertThat(output).contains("limiter_full org=acme method=GET route=/items request_id=");
        // One ERROR line for 200 requests, not 200 lines (and 200 pages).
        assertThat(output.toString().split("limiter_full", -1)).hasSize(2);

        // globex is still tracked and still limited (standard read burst is 5).
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/items").header("X-Api-Key", "key-globex")).andExpect(status().isOk());
        }
        mvc.perform(get("/items").header("X-Api-Key", "key-globex")).andExpect(status().isTooManyRequests());
    }

    @Test
    void limiterFullReportsHowManyLinesItSkipped(CapturedOutput output) throws Exception {
        // globex's total can't refill fully in 5 s, so it is never swept. Its
        // GET /items bucket does refill and is swept, freeing one slot: after the
        // advance acme's total takes it, and acme's endpoint still goes untracked.
        exhaustGlobexTotal();
        for (int i = 0; i < 50; i++) {
            mvc.perform(get("/items").header("X-Api-Key", "key-acme-1")).andExpect(status().isOk());
        }

        // After the interval, the next line reports how many were skipped.
        clock.advance(Duration.ofSeconds(5));
        mvc.perform(get("/items").header("X-Api-Key", "key-acme-1")).andExpect(status().isOk());
        assertThat(output).contains("suppressed_since_last=0").contains("suppressed_since_last=49");
    }

    @Test
    void aLimitedOrgCannotSlipThroughViaAnEndpointWithNoBucket() throws Exception {
        exhaustGlobexTotal();

        // GET /items/{id} has no bucket and there is no room for one, but the
        // org total exists and is empty, so the request is still rejected.
        mvc.perform(get("/items/1").header("X-Api-Key", "key-globex"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.binding_limit").value("org-total"));
    }
}
```

### `src/test/java/com/example/ratelimit/web/RateLimitMiddlewareTest.java`

```java
package com.example.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ratelimit.FakeClock;
import com.example.ratelimit.FakeClockConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end through Spring MVC with the test limits in ratelimit-test.yml:
 * standard = total 10 burst, read 5 burst, write 2 burst; POST /exports 1 burst.
 */
@SpringBootTest(properties = "RATELIMIT_FILE=classpath:ratelimit-test.yml")
@AutoConfigureMockMvc
@Import(FakeClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ExtendWith(OutputCaptureExtension.class)
class RateLimitMiddlewareTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeClock clock;

    private static MockHttpServletRequestBuilder as(String apiKey, MockHttpServletRequestBuilder req) {
        return req.header("X-Api-Key", apiKey);
    }

    @Test
    void missingOrUnknownKeyIs401() throws Exception {
        mvc.perform(get("/items")).andExpect(status().isUnauthorized());
        mvc.perform(as("nope", get("/items"))).andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflightIsNeverRejectedForMissingKey() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(options("/items")
                            .header("Origin", "https://app.example.com")
                            .header("Access-Control-Request-Method", "POST"))
                    // This demo has no CORS config, so Spring itself refuses the
                    // preflight (403). The point: auth and the limiter stay out of it.
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 429));
        }
    }

    @Test
    void healthNeedsNoKeyAndIsNeverLimited() throws Exception {
        for (int i = 0; i < 50; i++) {
            mvc.perform(get("/health")).andExpect(status().isOk());
        }
    }

    @Test
    void successfulResponsesCarryTheTightestLimit() throws Exception {
        mvc.perform(as("key-globex", get("/items")))
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit", "\"endpoint-read\";r=4;t=1"));
    }

    @Test
    void writesHitTheStricterWriteLimitWithAFull429(CapturedOutput output) throws Exception {
        mvc.perform(as("key-globex", post("/items"))).andExpect(status().isCreated());
        mvc.perform(as("key-globex", post("/items"))).andExpect(status().isCreated());
        mvc.perform(as("key-globex", post("/items")).header("X-Request-Id", "req-123"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2")) // 30/min = one token per 2s
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("RateLimit", "\"endpoint-write\";r=0;t=2"))
                .andExpect(jsonPath("$.error").value("rate_limited"))
                .andExpect(jsonPath("$.binding_limit").value("endpoint-write"))
                .andExpect(jsonPath("$.violated_limits", contains("endpoint-write")))
                .andExpect(jsonPath("$.org_id").value("globex"))
                .andExpect(jsonPath("$.tier").value("standard"))
                .andExpect(jsonPath("$.limit.per_minute").value(30))
                .andExpect(jsonPath("$.limit.burst").value(2))
                .andExpect(jsonPath("$.endpoint").value("POST /items"))
                .andExpect(jsonPath("$.retry_after_seconds").value(2))
                .andExpect(jsonPath("$.request_id").value("req-123"));
        // Support can find the log line from the ID the customer was given.
        assertThat(output).contains("rate_limited org=globex").contains("request_id=req-123");
    }

    @Test
    void anUnsafeRequestIdIsReplacedNotLogged(CapturedOutput output) throws Exception {
        mvc.perform(as("key-globex", post("/items")));
        mvc.perform(as("key-globex", post("/items")));
        // A client must not be able to plant fake key=value pairs in the log line.
        mvc.perform(as("key-globex", post("/items")).header("X-Request-Id", "x org=acme limit=none"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.request_id").value(matchesPattern("[0-9a-f-]{36}")));
        assertThat(output).doesNotContain("org=acme limit=none");
    }

    @Test
    void waitingRetryAfterSecondsIsEnough() throws Exception {
        mvc.perform(as("key-globex", post("/items")));
        mvc.perform(as("key-globex", post("/items")));
        mvc.perform(as("key-globex", post("/items"))).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(2));
        mvc.perform(as("key-globex", post("/items"))).andExpect(status().isCreated());
    }

    @Test
    void rejectedWritesDoNotSpendTheOrgTotal() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(as("key-globex", post("/items"))); // 2 allowed, 18 rejected
        }
        // Org total burst is 10. Had the 18 rejected writes been charged it would be empty;
        // instead only the 2 allowed writes count. 5 reads + 1 more leave 10 - 2 - 6 = 2.
        for (int i = 0; i < 5; i++) {
            mvc.perform(as("key-globex", get("/items/" + i))).andExpect(status().isOk());
        }
        mvc.perform(as("key-globex", get("/items")))
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit", startsWith("\"org-total\";r=2;")));
    }

    @Test
    void rawPathsShareOneRouteTemplateBucket() throws Exception {
        for (int i = 1; i <= 5; i++) {
            mvc.perform(as("key-globex", get("/items/" + i))).andExpect(status().isOk());
        }
        // /items/6 is a different URL but the same endpoint, GET /items/{id}.
        mvc.perform(as("key-globex", get("/items/6")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.endpoint").value("GET /items/{id}"));
    }

    @Test
    void headSharesTheGetBucket() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(as("key-globex", get("/items"))).andExpect(status().isOk());
            mvc.perform(as("key-globex", head("/items"))).andExpect(status().isOk());
        }
        mvc.perform(as("key-globex", get("/items"))).andExpect(status().isOk());
        // Read burst is 5, spent across GET and HEAD. (OPTIONS is answered by
        // Spring's built-in handler, which has no route, so like a 404 it is
        // charged to the org total only.)
        mvc.perform(as("key-globex", head("/items")))
                .andExpect(status().isTooManyRequests()) // HEAD has no body to check
                .andExpect(header().string("RateLimit", startsWith("\"endpoint-read\";r=0;")));
    }

    @Test
    void routeOverrideAppliesToOneEndpoint() throws Exception {
        mvc.perform(as("key-globex", post("/exports"))).andExpect(status().isAccepted());
        mvc.perform(as("key-globex", post("/exports")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "10"))
                .andExpect(jsonPath("$.binding_limit").value("route:POST /exports"));
        // Other writes are unaffected.
        mvc.perform(as("key-globex", post("/items"))).andExpect(status().isCreated());
    }

    @Test
    void keysOfOneOrgShareItsLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(as(i % 2 == 0 ? "key-acme-1" : "key-acme-2", post("/items"))).andExpect(status().isCreated());
        }
        // Premium write burst is 20: 10 + 10 more from either key, then 429.
        for (int i = 0; i < 10; i++) {
            mvc.perform(as("key-acme-2", post("/items"))).andExpect(status().isCreated());
        }
        mvc.perform(as("key-acme-1", post("/items"))).andExpect(status().isTooManyRequests());
    }

    @Test
    void aNoisyOrgDoesNotAffectAnotherOrg() throws Exception {
        for (int i = 0; i < 30; i++) {
            mvc.perform(as("key-globex", get("/items")));
        }
        mvc.perform(as("key-globex", get("/items"))).andExpect(status().isTooManyRequests());
        mvc.perform(as("key-acme-1", get("/items"))).andExpect(status().isOk());
    }

    @Test
    void unmatchedPathsStillCountTowardTheOrgTotal() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(as("key-globex", get("/no-such-route/" + i))).andExpect(status().isNotFound());
        }
        mvc.perform(as("key-globex", get("/no-such-route/x")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.binding_limit").value("org-total"));
    }
}
```

