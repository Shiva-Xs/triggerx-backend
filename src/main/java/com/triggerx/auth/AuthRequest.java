package com.triggerx.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class AuthRequest {

    public record OtpSend(
            @NotBlank(message = "email is required")
            @Email(message = "Please enter a valid email address")
            String email
    ) {}

    public record OtpVerify(
            @NotBlank(message = "email is required")
            @Email(message = "Please enter a valid email address")
            String email,

            @NotBlank(message = "otp is required")
            @Pattern(regexp = "^\\d{6}$", message = "OTP must be exactly 6 digits")
            String otp
    ) {}
}
