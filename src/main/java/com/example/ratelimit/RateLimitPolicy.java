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
