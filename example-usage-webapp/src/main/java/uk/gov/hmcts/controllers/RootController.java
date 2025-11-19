package uk.gov.hmcts.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RootController {

    @GetMapping("/")
    public ResponseEntity<Map<String, String>> root(@RequestParam(defaultValue = "world") final String name) {
        return ResponseEntity.ok(Map.of("message", "Hello " + name));
    }

    @PostMapping("/echo")
    public ResponseEntity<Map<String, Map<String, Object>>> echo(@RequestBody final Map<String, Object> body) {
        return ResponseEntity.ok(Map.of("received", body));
    }

    @GetMapping("/error")
    public ResponseEntity<Map<String, String>> error() {
        return ResponseEntity.status(400).body(Map.of("error", "error message"));
    }
}
