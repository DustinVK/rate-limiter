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
