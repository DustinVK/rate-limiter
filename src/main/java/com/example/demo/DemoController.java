package com.example.demo;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-in API. Note there is no rate-limiting code here: the interceptor
 * covers every route.
 */
@RestController
public class DemoController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/items")
    public Map<String, Object> listItems() {
        return Map.of("items", java.util.List.of());
    }

    @GetMapping("/items/{id}")
    public Map<String, String> getItem(@PathVariable String id) {
        return Map.of("id", id);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> createItem() {
        return Map.of("status", "created");
    }

    @PutMapping("/items/{id}")
    public Map<String, String> updateItem(@PathVariable String id) {
        return Map.of("id", id, "status", "updated");
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable String id) {
    }

    @PostMapping("/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> startExport() {
        return Map.of("status", "export started");
    }
}
