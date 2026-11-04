package com.example.ratelimit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"RATELIMIT_FILE=classpath:ratelimit-test.yml", "ratelimit.enforce=false"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ShadowModeTest {

    @Autowired
    MockMvc mvc;

    @Test
    void shadowModeLogsWouldBeRejectionsButIsInvisibleToClients(CapturedOutput output) throws Exception {
        for (int i = 0; i < 10; i++) {
            // No RateLimit header either: a client that paces itself on it would
            // slow down during a trial that is supposed to block nothing.
            mvc.perform(post("/items").header("X-Api-Key", "key-globex"))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("RateLimit"));
        }
        assertThat(output).contains("rate_limited org=globex tier=standard limit=endpoint-write")
                .contains("enforce=false");
    }
}
