package com.example.ratelimit;

/**
 * One bucket a request must pass.
 *
 * @param key   identity of the bucket in memory, e.g. {@code org:acme:POST /items}
 * @param name  the limit's public name, returned to clients, e.g. {@code endpoint-write}
 * @param limit rate and burst for this bucket
 */
public record BucketSpec(String key, String name, Limit limit) {
}
