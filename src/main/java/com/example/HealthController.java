package com.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "tekton");
        result.put("time", LocalDateTime.now().toString());
        return result;
    }

    @GetMapping("/")
    public String index() {
        return "Hello from Tekton CI/CD!";
    }
}
