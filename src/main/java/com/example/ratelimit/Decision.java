package com.example.ratelimit;

import java.util.Comparator;
import java.util.List;

/** The outcome of checking one request against all of its buckets. */
public record Decision(Outcome outcome, List<BucketResult> results) {

    public enum Outcome {
        ALLOWED,
        RATE_LIMITED,
        /** The limiter is at max-buckets and could not free room for a new bucket. */
        LIMITER_FULL
    }

    /**
     * @param remaining       tokens left after this request (unchanged if it was rejected)
     * @param nanosUntilToken 0 if this bucket allowed the request, else the wait for one token
     * @param nanosUntilFull  wait until the bucket is completely refilled
     */
    public record BucketResult(BucketSpec spec, double remaining, long nanosUntilToken, long nanosUntilFull) {
        public boolean exceeded() {
            return nanosUntilToken > 0;
        }
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOWED;
    }

    /** Buckets that rejected the request. */
    public List<BucketResult> exceeded() {
        return results.stream().filter(BucketResult::exceeded).toList();
    }

    /**
     * The limit that actually decides when the client may retry: of the buckets
     * that rejected the request, the one with the longest wait. Retrying when
     * the first-tripped bucket refills would just hit the next one.
     */
    public BucketResult binding() {
        return exceeded().stream().max(Comparator.comparingLong(BucketResult::nanosUntilToken)).orElseThrow();
    }

    /** The bucket with the fewest tokens left, reported in RateLimit headers on success. */
    public BucketResult tightest() {
        return results.stream().min(Comparator.comparingDouble(BucketResult::remaining)).orElseThrow();
    }
}
