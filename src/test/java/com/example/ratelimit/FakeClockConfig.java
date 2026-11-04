package com.example.ratelimit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock with a {@link FakeClock}; {@code @Import} it into a Spring test. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeClockConfig {

    @Bean
    @Primary
    FakeClock fakeClock() {
        return new FakeClock();
    }
}
