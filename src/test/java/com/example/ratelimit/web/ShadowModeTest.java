package com.example.ratelimit.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"RATELIMIT_FILE=classpath:ratelimit-test.yml", "ratelimit.enforce=false"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ShadowModeTest {

    @Autowired
    MockMvc mvc;

    @Test
    void shadowModeLogsWouldBeRejectionsButBlocksNothing(CapturedOutput output) throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/items").header("X-Api-Key", "key-globex")).andExpect(status().isCreated());
        }
        assertThat(output).contains("rate_limited org=globex tier=standard limit=endpoint-write");
        assertThat(output).contains("enforce=false");
    }
}
