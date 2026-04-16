package com.triggerx.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/otp/send")
    public ResponseEntity<Map<String, Object>> sendOtp(
            @Valid @RequestBody AuthRequest.OtpSend request
    ) {
        authService.sendOtp(request.email());
        return ResponseEntity.ok(Map.of(
                "message", "OTP sent",
                "expiresInSeconds", authService.getOtpExpirySeconds()
        ));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<OtpVerifyResponse> verifyOtp(
            @Valid @RequestBody AuthRequest.OtpVerify request
    ) {
        OtpVerifyResponse response = authService.verifyOtp(request.email(), request.otp());
        return ResponseEntity.ok(response);
    }
}
