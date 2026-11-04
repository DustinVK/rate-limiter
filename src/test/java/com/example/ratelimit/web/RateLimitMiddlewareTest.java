package com.example.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ratelimit.FakeClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end through Spring MVC with the test limits in ratelimit-test.yml:
 * standard = total 10 burst, read 5 burst, write 2 burst; POST /exports 1 burst.
 */
@SpringBootTest(properties = "RATELIMIT_FILE=classpath:ratelimit-test.yml")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ExtendWith(OutputCaptureExtension.class)
class RateLimitMiddlewareTest {

    @TestConfiguration
    static class Clock {
        @Bean
        @Primary
        FakeClock fakeClock() {
            return new FakeClock();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeClock clock;

    private static MockHttpServletRequestBuilder as(String apiKey, MockHttpServletRequestBuilder req) {
        return req.header("X-Api-Key", apiKey);
    }

    @Test
    void missingOrUnknownKeyIs401() throws Exception {
        mvc.perform(get("/items")).andExpect(status().isUnauthorized());
        mvc.perform(as("nope", get("/items"))).andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflightIsNeverRejectedForMissingKey() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(options("/items")
                            .header("Origin", "https://app.example.com")
                            .header("Access-Control-Request-Method", "POST"))
                    // This demo has no CORS config, so Spring itself refuses the
                    // preflight (403). The point: auth and the limiter stay out of it.
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 429));
        }
    }

    @Test
    void healthNeedsNoKeyAndIsNeverLimited() throws Exception {
        for (int i = 0; i < 50; i++) {
            mvc.perform(get("/health")).andExpect(status().isOk());
        }
    }

    @Test
    void successfulResponsesCarryTheTightestLimit() throws Exception {
        mvc.perform(as("key-globex", get("/items")))
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit", "\"endpoint-read\";r=4;t=1"));
    }

    @Test
    void writesHitTheStricterWriteLimitWithAFull429(CapturedOutput output) throws Exception {
        mvc.perform(as("key-globex", post("/items"))).andExpect(status().isCreated());
        mvc.perform(as("key-globex", post("/items"))).andExpect(status().isCreated());
        mvc.perform(as("key-globex", post("/items")).header("X-Request-Id", "req-123"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2")) // 30/min = one token per 2s
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("RateLimit", "\"endpoint-write\";r=0;t=2"))
                .andExpect(jsonPath("$.error").value("rate_limited"))
                .andExpect(jsonPath("$.binding_limit").value("endpoint-write"))
                .andExpect(jsonPath("$.violated_limits", contains("endpoint-write")))
                .andExpect(jsonPath("$.org_id").value("globex"))
                .andExpect(jsonPath("$.tier").value("standard"))
                .andExpect(jsonPath("$.limit.per_minute").value(30))
                .andExpect(jsonPath("$.limit.burst").value(2))
                .andExpect(jsonPath("$.endpoint").value("POST /items"))
                .andExpect(jsonPath("$.retry_after_seconds").value(2))
                .andExpect(jsonPath("$.request_id").value("req-123"));
        // Support can find the log line from the ID the customer was given.
        assertThat(output).contains("rate_limited org=globex").contains("request_id=req-123");
    }

    @Test
    void waitingRetryAfterSecondsIsEnough() throws Exception {
        mvc.perform(as("key-globex", post("/items")));
        mvc.perform(as("key-globex", post("/items")));
        mvc.perform(as("key-globex", post("/items"))).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(2));
        mvc.perform(as("key-globex", post("/items"))).andExpect(status().isCreated());
    }

    @Test
    void rejectedWritesDoNotSpendTheOrgTotal() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(as("key-globex", post("/items"))); // 2 allowed, 18 rejected
        }
        // Org total burst is 10. Had the 18 rejected writes been charged it would be empty;
        // instead only the 2 allowed writes count. 5 reads + 1 more leave 10 - 2 - 6 = 2.
        for (int i = 0; i < 5; i++) {
            mvc.perform(as("key-globex", get("/items/" + i))).andExpect(status().isOk());
        }
        mvc.perform(as("key-globex", get("/items")))
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit", startsWith("\"org-total\";r=2;")));
    }

    @Test
    void rawPathsShareOneRouteTemplateBucket() throws Exception {
        for (int i = 1; i <= 5; i++) {
            mvc.perform(as("key-globex", get("/items/" + i))).andExpect(status().isOk());
        }
        // /items/6 is a different URL but the same endpoint, GET /items/{id}.
        mvc.perform(as("key-globex", get("/items/6")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.endpoint").value("GET /items/{id}"));
    }

    @Test
    void routeOverrideAppliesToOneEndpoint() throws Exception {
        mvc.perform(as("key-globex", post("/exports"))).andExpect(status().isAccepted());
        mvc.perform(as("key-globex", post("/exports")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "10"))
                .andExpect(jsonPath("$.binding_limit").value("route:POST /exports"));
        // Other writes are unaffected.
        mvc.perform(as("key-globex", post("/items"))).andExpect(status().isCreated());
    }

    @Test
    void keysOfOneOrgShareItsLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(as(i % 2 == 0 ? "key-acme-1" : "key-acme-2", post("/items"))).andExpect(status().isCreated());
        }
        // Premium write burst is 20: 10 + 10 more from either key, then 429.
        for (int i = 0; i < 10; i++) {
            mvc.perform(as("key-acme-2", post("/items"))).andExpect(status().isCreated());
        }
        mvc.perform(as("key-acme-1", post("/items"))).andExpect(status().isTooManyRequests());
    }

    @Test
    void aNoisyOrgDoesNotAffectAnotherOrg() throws Exception {
        for (int i = 0; i < 30; i++) {
            mvc.perform(as("key-globex", get("/items")));
        }
        mvc.perform(as("key-globex", get("/items"))).andExpect(status().isTooManyRequests());
        mvc.perform(as("key-acme-1", get("/items"))).andExpect(status().isOk());
    }

    @Test
    void unmatchedPathsStillCountTowardTheOrgTotal() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(as("key-globex", get("/no-such-route/" + i))).andExpect(status().isNotFound());
        }
        mvc.perform(as("key-globex", get("/no-such-route/x")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.binding_limit").value("org-total"));
    }
}
