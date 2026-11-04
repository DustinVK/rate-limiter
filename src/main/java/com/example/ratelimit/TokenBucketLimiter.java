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
 *       section is a few arithmetic and map operations.</li>
 *   <li>Only fully refilled buckets are ever evicted. A full bucket behaves
 *       exactly like a missing one, so dropping it changes nothing; dropping a
 *       depleted one would hand that org a fresh allowance.</li>
 * </ul>
 */
public final class TokenBucketLimiter {

    /** Sweep for fully refilled buckets after this many checks. */
    static final int SWEEP_EVERY = 1024;

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

    public TokenBucketLimiter(NanoClock clock, int maxBuckets) {
        this.clock = clock;
        this.maxBuckets = maxBuckets;
    }

    public synchronized Decision tryAcquire(List<BucketSpec> specs) {
        long now = clock.nanoTime();
        if (++checksSinceSweep >= SWEEP_EVERY) {
            sweep(now);
        }

        long newKeys = specs.stream().filter(s -> !buckets.containsKey(s.key())).count();
        if (newKeys > 0 && buckets.size() + newKeys > maxBuckets) {
            sweep(now);
            if (buckets.size() + newKeys > maxBuckets) {
                return new Decision(Decision.Outcome.LIMITER_FULL, List.of());
            }
        }

        // Phase 1: evaluate every bucket without changing anything.
        double[] available = new double[specs.size()];
        boolean allowed = true;
        for (int i = 0; i < specs.size(); i++) {
            Bucket b = buckets.get(specs.get(i).key());
            available[i] = b == null ? specs.get(i).limit().burst() : b.tokensAt(now);
            if (available[i] < 1) {
                allowed = false;
            }
        }

        // Phase 2: take one token from every bucket, or from none.
        List<Decision.BucketResult> results = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            BucketSpec spec = specs.get(i);
            Limit limit = spec.limit();
            double remaining = available[i];
            if (allowed) {
                remaining -= 1;
                Bucket b = buckets.get(spec.key());
                if (b == null) {
                    buckets.put(spec.key(), new Bucket(limit, remaining, now));
                } else {
                    b.tokens = remaining;
                    b.updatedAt = now;
                }
            }
            long untilToken = allowed ? 0 : limit.nanosUntilOneToken(available[i]);
            results.add(new Decision.BucketResult(spec, remaining, untilToken, limit.nanosUntilFull(remaining)));
        }
        return new Decision(allowed ? Decision.Outcome.ALLOWED : Decision.Outcome.RATE_LIMITED, results);
    }

    /** Drop buckets that have fully refilled. O(buckets) while holding the lock. */
    private void sweep(long now) {
        checksSinceSweep = 0;
        buckets.values().removeIf(b -> b.tokensAt(now) >= b.limit.burst());
    }

    public synchronized int size() {
        return buckets.size();
    }
}
