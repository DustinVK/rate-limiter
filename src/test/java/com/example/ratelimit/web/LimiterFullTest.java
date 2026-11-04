package com.example.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ratelimit.FakeClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/** A full limiter must not become an outage for orgs it can't track. */
@SpringBootTest(properties = {"RATELIMIT_FILE=classpath:ratelimit-test.yml", "ratelimit.max-buckets=2"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LimiterFullTest {

    @TestConfiguration
    static class Clock {
        @Bean
        @Primary
        FakeClock fakeClock() {
            return new FakeClock(); // frozen, so globex's buckets never refill and get swept
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void newOrgIsLetThroughAndLoggedWhenTheCapIsFull(CapturedOutput output) throws Exception {
        // globex fills the cap: one request creates org-total + GET /items.
        mvc.perform(get("/items").header("X-Api-Key", "key-globex")).andExpect(status().isOk());

        // acme needs two new buckets and there is no room, so it goes through
        // untracked: more requests than any of its limits would allow.
        for (int i = 0; i < 200; i++) {
            mvc.perform(get("/items").header("X-Api-Key", "key-acme-1")).andExpect(status().isOk());
        }
        assertThat(output).contains("limiter_full org=acme method=GET route=/items");

        // globex is still tracked and still limited (standard read burst is 5).
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/items").header("X-Api-Key", "key-globex")).andExpect(status().isOk());
        }
        mvc.perform(get("/items").header("X-Api-Key", "key-globex")).andExpect(status().isTooManyRequests());
    }
}
