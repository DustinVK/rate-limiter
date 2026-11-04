package com.example.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ratelimit.RateLimitPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Loads the real ./ratelimit.yml, not the test copy, so a typo in the file an
 * operator edits fails the build instead of a deploy.
 */
@SpringBootTest
class ShippedConfigTest {

    @Autowired
    RateLimitPolicy policy;

    @Test
    void shippedConfigStartsAndKeepsWritesStricterThanReads() {
        for (String org : new String[] {"acme", "globex", "initech"}) {
            RateLimitPolicy.OrgLimits limits = policy.limitsFor(org);
            assertThat(limits.write().perMinute()).as(org).isLessThan(limits.read().perMinute());
            assertThat(limits.read().perMinute()).as(org).isLessThanOrEqualTo(limits.total().perMinute());
        }
    }
}
