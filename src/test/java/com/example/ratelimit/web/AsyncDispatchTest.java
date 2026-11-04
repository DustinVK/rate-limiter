package com.example.ratelimit.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Async handlers are dispatched twice; the request must be charged once. */
@SpringBootTest(properties = "RATELIMIT_FILE=classpath:ratelimit-test.yml")
@AutoConfigureMockMvc
@Import(AsyncDispatchTest.AsyncController.class)
class AsyncDispatchTest {

    @RestController
    static class AsyncController {
        @GetMapping("/async-test")
        Callable<String> slow() {
            return () -> "done";
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void anAsyncRequestSpendsOneToken() throws Exception {
        // Standard read burst is 5. Charged twice, the 3rd request would get
        // a 429 on its completion dispatch, after the work already ran.
        for (int i = 0; i < 5; i++) {
            MvcResult started = mvc.perform(get("/async-test").header("X-Api-Key", "key-globex"))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        }
        mvc.perform(get("/async-test").header("X-Api-Key", "key-globex")).andExpect(status().isTooManyRequests());
    }
}
