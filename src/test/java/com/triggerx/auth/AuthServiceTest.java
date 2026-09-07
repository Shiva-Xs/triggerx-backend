package com.triggerx.auth;

import com.triggerx.common.TriggerXException;
import com.triggerx.notification.EmailSender;
import com.triggerx.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private OtpTokenRepository otpTokenRepository;
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private JwtService jwtService;
    
    @Mock
    private EmailSender emailSender;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "otpRateLimitPerHour", 3);
        ReflectionTestUtils.setField(authService, "otpRateLimitPerIpPerHour", 30);
        ReflectionTestUtils.setField(authService, "otpExpiryMinutes", 10);
        ReflectionTestUtils.setField(authService, "otpMaxAttempts", 5);
    }

    @Test
    void sendOtp_BlocksAfterRateLimitReached() {
        when(otpTokenRepository.countByEmailSince(eq("test@gmail.com"), any(LocalDateTime.class)))
                .thenReturn(3L);

        TriggerXException ex = assertThrows(TriggerXException.class, () -> 
                authService.sendOtp("test@gmail.com", "1.2.3.4"));

        assertEquals("RATE_LIMITED", ex.getErrorCode());
    }

    @Test
    void verifyOtp_WrongOtpIncrementsAttempts() {
        OtpToken token = new OtpToken();
        token.setEmail("test@gmail.com");
        token.setOtpHash("wronghash");
        token.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        token.setAttempts(3);

        when(otpTokenRepository.findFirstByEmailAndInvalidatedAtIsNullOrderByCreatedAtDesc("test@gmail.com"))
                .thenReturn(Optional.of(token));

        TriggerXException ex = assertThrows(TriggerXException.class, () -> 
                authService.verifyOtp("test@gmail.com", "123456"));

        assertEquals("INVALID_OTP", ex.getErrorCode());
        assertEquals(4, token.getAttempts(), "Attempts should be incremented");
        assertEquals(1, ex.getAttemptsRemaining(), "Should have 1 attempt remaining");
    }

    /**
     * Per-address limiting alone let one host spray codes at unlimited addresses, sending all of
     * that mail from our own account.
     */
    @Test
    void sendOtp_blocksOneAddressSprayingManyEmails() {
        ReflectionTestUtils.setField(authService, "otpRateLimitPerIpPerHour", 3);
        when(otpTokenRepository.countByEmailSince(anyString(), any(LocalDateTime.class)))
                .thenReturn(0L);

        for (int i = 0; i < 3; i++) {
            authService.sendOtp("victim" + i + "@gmail.com", "9.9.9.9");
        }

        TriggerXException ex = assertThrows(TriggerXException.class, () ->
                authService.sendOtp("victim99@gmail.com", "9.9.9.9"));
        assertEquals("RATE_LIMITED", ex.getErrorCode());
    }

    /** A different caller is unaffected by someone else's bucket. */
    @Test
    void sendOtp_perIpLimitDoesNotLeakAcrossCallers() {
        ReflectionTestUtils.setField(authService, "otpRateLimitPerIpPerHour", 1);
        when(otpTokenRepository.countByEmailSince(anyString(), any(LocalDateTime.class)))
                .thenReturn(0L);

        authService.sendOtp("a@gmail.com", "1.1.1.1");
        assertThrows(TriggerXException.class, () -> authService.sendOtp("b@gmail.com", "1.1.1.1"));

        authService.sendOtp("c@gmail.com", "2.2.2.2");
    }
}
