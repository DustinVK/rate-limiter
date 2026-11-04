package com.example.ratelimit;

import jakarta.validation.constraints.Positive;

/**
 * A token bucket: holds up to {@code burst} tokens and refills at
 * {@code perMinute} tokens per minute. Each request spends one token.
 */
public record Limit(@Positive int perMinute, @Positive int burst) {

    private static final double NANOS_PER_MINUTE = 60e9;

    double tokensPerNano() {
        return perMinute / NANOS_PER_MINUTE;
    }

    /** Nanoseconds until a bucket holding {@code tokens} has at least one token. */
    long nanosUntilOneToken(double tokens) {
        return tokens >= 1 ? 0 : (long) Math.ceil((1 - tokens) / tokensPerNano());
    }

    /** Nanoseconds until a bucket holding {@code tokens} is full again. */
    long nanosUntilFull(double tokens) {
        return tokens >= burst ? 0 : (long) Math.ceil((burst - tokens) / tokensPerNano());
    }
}
