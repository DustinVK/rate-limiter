package com.example.ratelimit;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** The outcome of checking one request against all of its buckets. */
public record Decision(Outcome outcome, List<BucketResult> results) {

    public enum Outcome {
        ALLOWED,
        /**
         * Allowed, but the limiter is at max-buckets and could not store some of
         * the request's buckets. The buckets that exist were still checked and
         * charged; the missing ones are not tracked and not in the results.
         */
        ALLOWED_UNTRACKED,
        RATE_LIMITED
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
        return outcome != Outcome.RATE_LIMITED;
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

    /**
     * The bucket with the fewest tokens left, reported in RateLimit headers on
     * success. Empty when none of the request's buckets could be tracked.
     */
    public Optional<BucketResult> tightest() {
        return results.stream().min(Comparator.comparingDouble(BucketResult::remaining));
    }
}
