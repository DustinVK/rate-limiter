package com.example.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.RateLimiterApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/** Bad config must stop the service from starting, never mean "no limit". */
class ConfigValidationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(RateLimitWebConfig.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withPropertyValues(
                    "ratelimit.default-tier=standard",
                    "ratelimit.tiers.standard.total.per-minute=10", "ratelimit.tiers.standard.total.burst=1",
                    "ratelimit.tiers.standard.read.per-minute=10", "ratelimit.tiers.standard.read.burst=1",
                    "ratelimit.tiers.standard.write.per-minute=10", "ratelimit.tiers.standard.write.burst=1",
                    "ratelimit.api-keys.k=org");

    @Test
    void validConfigStarts() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void zeroRateRefusesToStart() {
        runner.withPropertyValues("ratelimit.tiers.standard.write.per-minute=0")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("perMinute"));
    }

    @Test
    void unknownTierRefusesToStart() {
        runner.withPropertyValues("ratelimit.orgs.acme.tier=platinum")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("platinum"));
    }

    /** The edit an operator makes at 11pm: a typo must not silently keep the old limit. */
    @Test
    void misspelledFieldRefusesToStart() {
        runner.withPropertyValues("ratelimit.orgs.acme.tier=standard", "ratelimit.orgs.acme.wrte.per-minute=1")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("wrte"));
    }

    @Test
    void routeOverrideForAMissingRouteRefusesToStart() {
        // The real route is POST /exports.
        assertThatThrownBy(() -> new SpringApplicationBuilder(RateLimiterApplication.class)
                .properties("RATELIMIT_FILE=classpath:ratelimit-test.yml", "server.port=0")
                .run("--ratelimit.route-overrides[0].method=POST", "--ratelimit.route-overrides[0].path=/export",
                        "--ratelimit.route-overrides[0].per-minute=6", "--ratelimit.route-overrides[0].burst=1")
                .close())
                .hasStackTraceContaining("no controller serves 'POST /export'");
    }
}
