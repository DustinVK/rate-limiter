package com.example.ratelimit;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** A clock tests move by hand, so nothing ever sleeps. */
public final class FakeClock implements NanoClock {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    @Override
    public long nanoTime() {
        return now.get();
    }

    public void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }
}
