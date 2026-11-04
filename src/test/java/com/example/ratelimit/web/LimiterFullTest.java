package com.example.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ratelimit.FakeClock;
import com.example.ratelimit.FakeClockConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** A full limiter must not become an outage for orgs it can't track. */
@SpringBootTest(properties = {"RATELIMIT_FILE=classpath:ratelimit-test.yml", "ratelimit.max-buckets=2"})
@AutoConfigureMockMvc
@Import(FakeClockConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LimiterFullTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeClock clock;

    /**
     * globex fills the cap (max-buckets=2: org-total + GET /items) and empties its
     * total (burst 10): 5 reads empty GET /items, 5 unmatched paths hit only the total.
     */
    private void exhaustGlobexTotal() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/items").header("X-Api-Key", "key-globex")).andExpect(status().isOk());
        }
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/no-such-route").header("X-Api-Key", "key-globex")).andExpect(status().isNotFound());
        }
    }

    @Test
    void newOrgIsLetThroughAndLoggedWhenTheCapIsFull(CapturedOutput output) throws Exception {
        // globex fills the cap: one request creates org-total + GET /items.
        mvc.perform(get("/items").header("X-Api-Key", "key-globex")).andExpect(status().isOk());

        // acme needs two new buckets and there is no room, so it goes through
        // untracked: more requests than any of its limits would allow.
        for (int i = 0; i < 200; i++) {
            mvc.perform(get("/items").header("X-Api-Key", "key-acme-1")).andExpect(status().isOk());
        }
        assertThat(output).contains("limiter_full org=acme method=GET route=/items request_id=");
        // One ERROR line for 200 requests, not 200 lines (and 200 pages).
        assertThat(output.toString().split("limiter_full", -1)).hasSize(2);

        // globex is still tracked and still limited (standard read burst is 5).
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/items").header("X-Api-Key", "key-globex")).andExpect(status().isOk());
        }
        mvc.perform(get("/items").header("X-Api-Key", "key-globex")).andExpect(status().isTooManyRequests());
    }

    @Test
    void limiterFullReportsHowManyLinesItSkipped(CapturedOutput output) throws Exception {
        // globex's total can't refill fully in 5 s, so it is never swept. Its
        // GET /items bucket does refill and is swept, freeing one slot: after the
        // advance acme's total takes it, and acme's endpoint still goes untracked.
        exhaustGlobexTotal();
        for (int i = 0; i < 50; i++) {
            mvc.perform(get("/items").header("X-Api-Key", "key-acme-1")).andExpect(status().isOk());
        }

        // After the interval, the next line reports how many were skipped.
        clock.advance(Duration.ofSeconds(5));
        mvc.perform(get("/items").header("X-Api-Key", "key-acme-1")).andExpect(status().isOk());
        assertThat(output).contains("suppressed_since_last=0").contains("suppressed_since_last=49");
    }

    @Test
    void aLimitedOrgCannotSlipThroughViaAnEndpointWithNoBucket() throws Exception {
        exhaustGlobexTotal();

        // GET /items/{id} has no bucket and there is no room for one, but the
        // org total exists and is empty, so the request is still rejected.
        mvc.perform(get("/items/1").header("X-Api-Key", "key-globex"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.binding_limit").value("org-total"));
    }
}
