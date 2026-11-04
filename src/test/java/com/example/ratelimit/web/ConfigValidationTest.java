package com.example.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.RateLimiterApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;

/** Bad config must stop the service from starting, never mean "no limit". */
class ConfigValidationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(RateLimitWebConfig.class)
            .withPropertyValues(
                    "ratelimit.default-tier=standard",
                    "ratelimit.tiers.standard.total.per-minute=10", "ratelimit.tiers.standard.total.burst=1",
                    "ratelimit.tiers.standard.read.per-minute=10", "ratelimit.tiers.standard.read.burst=1",
                    "ratelimit.tiers.standard.write.per-minute=10", "ratelimit.tiers.standard.write.burst=1",
                    "ratelimit.api-keys.k=org");

    @Test
    void validConfigStarts() {
        runner.withBean(tools.jackson.databind.json.JsonMapper.class, () -> tools.jackson.databind.json.JsonMapper.builder().build())
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void zeroRateRefusesToStart() {
        runner.withBean(tools.jackson.databind.json.JsonMapper.class, () -> tools.jackson.databind.json.JsonMapper.builder().build())
                .withPropertyValues("ratelimit.tiers.standard.write.per-minute=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void unknownTierRefusesToStart() {
        runner.withBean(tools.jackson.databind.json.JsonMapper.class, () -> tools.jackson.databind.json.JsonMapper.builder().build())
                .withPropertyValues("ratelimit.orgs.acme.tier=platinum")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("platinum"));
    }
}
