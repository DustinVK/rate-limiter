package com.example.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

        // ...and the total bucket still has 9, not 8.
        Decision read = limiter.tryAcquire(List.of(total));
        assertThat(read.results().get(0).remaining()).isEqualTo(8.0); // 9 before this request
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
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int i = 0; i < 500; i++) {
            pool.submit(() -> {
                start.await();
                if (limiter.tryAcquire(specs).allowed()) {
                    allowed.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(allowed.get()).isEqualTo(10); // clock is frozen: burst only
    }
}
