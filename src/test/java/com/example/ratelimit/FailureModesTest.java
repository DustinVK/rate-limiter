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
