package com.example.ratelimit;

/**
 * Monotonic time source. Production uses System::nanoTime, which never jumps
 * when NTP adjusts the wall clock; tests inject a clock they can advance.
 */
@FunctionalInterface
public interface NanoClock {
    long nanoTime();
}
