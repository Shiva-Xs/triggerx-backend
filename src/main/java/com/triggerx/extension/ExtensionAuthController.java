package com.triggerx.extension;

import com.triggerx.auth.OtpVerifyResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/extension")
@RequiredArgsConstructor
public class ExtensionAuthController {

    private final ExtensionAuthService extensionAuthService;

    @PostMapping("/auth-token")
    public ResponseEntity<Map<String, String>> generateAuthToken(
            @AuthenticationPrincipal UUID userId) {
        String token = extensionAuthService.generateExchangeToken(userId);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "redeemUrl", "/api/v1/extension/redeem"
        ));
    }

    @PostMapping("/redeem")
    public ResponseEntity<OtpVerifyResponse> redeemToken(
            @Valid @RequestBody RedeemRequest request) {
        return extensionAuthService.redeemToken(request.token())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.GONE).build());
    }

    record RedeemRequest(
            @NotBlank(message = "token is required")
            String token
    ) {}
}
