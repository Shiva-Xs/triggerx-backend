package com.triggerx.alert;

import com.triggerx.ai.NaturalAlertService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;
    private final NaturalAlertService naturalAlertService;

    @PostMapping
    public ResponseEntity<AlertResponse> createAlert(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody AlertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(alertService.createAlert(userId, request));
    }

    @PostMapping("/natural")
    public ResponseEntity<AlertResponse> createNaturalAlert(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody NaturalInput request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(naturalAlertService.parseAndCreate(request.text(), userId));
    }

    @GetMapping
    public ResponseEntity<List<AlertResponse>> getAlerts(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) AlertStatus status) {
        return ResponseEntity.ok(alertService.getAlerts(userId, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AlertResponse> getAlert(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(alertService.getAlert(userId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAlert(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        alertService.deleteAlert(userId, id);
        return ResponseEntity.noContent().build();
    }

    record NaturalInput(
            @NotBlank(message = "text is required")
            @Size(max = 500, message = "text must be 500 characters or fewer")
            String text) {}
}
