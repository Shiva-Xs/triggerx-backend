package com.triggerx.price;

import com.triggerx.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/symbols")
@RequiredArgsConstructor
public class SymbolController {

    private final BinanceSymbolRegistry symbolRegistry;

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String q) {
        if (q.trim().length() < 2) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse("INVALID_REQUEST", "Query must be at least 2 characters", null));
        }
        List<String> results = symbolRegistry.search(q.trim());
        return ResponseEntity.ok(Map.of("results", results));
    }
}
