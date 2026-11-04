package com.example.ratelimit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory token buckets for one process.
 *
 * <p>All state lives in this object's heap, so it is lost on restart and not
 * shared between replicas. See README, "Known failure modes".
 *
 * <p>Correctness rules:
 * <ul>
 *   <li>A request passes only if every one of its buckets has a token. Tokens
 *       are taken from all of them or from none, so a rejected write never
 *       spends the org's total allowance, and rejected requests cost nothing.</li>
 *   <li>Refill is computed lazily from elapsed monotonic time; there are no
 *       timers.</li>
 *   <li>One lock guards check-and-take, insertion and eviction. The critical
 *       section is a few arithmetic and map operations, except sweeps, which
 *       are rate-limited to one per 100 ms. Next step: lock per org (all of a
 *       request's buckets belong to one org), so orgs never wait on each other.</li>
 *   <li>Only fully refilled buckets are ever evicted. A full bucket behaves
 *       exactly like a missing one, so dropping it changes nothing; dropping a
 *       depleted one would hand that org a fresh allowance.</li>
 * </ul>
 */
public final class TokenBucketLimiter {

    /** Sweep for fully refilled buckets after this many checks... */
    static final int SWEEP_EVERY = 1024;

    /**
     * ...but never more often than this. A sweep scans every bucket while
     * holding the one lock, so under a flood of new keys at the cap, sweeping
     * on every request would stall every org at once.
     */
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
        // remains; the rest go untracked and are left out of the results. The
        // buckets that exist are always enforced, so an org with an empty total
        // can't slip through via an endpoint it hasn't called recently.
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
