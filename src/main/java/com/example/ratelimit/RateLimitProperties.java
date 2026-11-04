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
@ConfigurationProperties("ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enforce,
        @DefaultValue("10000") @Positive int maxBuckets,
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
