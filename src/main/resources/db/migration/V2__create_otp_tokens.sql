-- email is NOT a FK to users.
--   The user row doesn't exist yet on first login —
--   it gets created only after OTP is successfully verified.
--
-- otp_hash: SHA-256(rawOtp + "|" + email). Raw OTP is never stored.
--
-- invalidated_at: soft-delete, never hard-delete.
--   Rate limiting counts all rows WHERE email = ? AND created_at > NOW() - 1h.
--   Hard-deleting rows would destroy that count.

CREATE TABLE otp_tokens (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email          VARCHAR(255) NOT NULL,
    otp_hash       VARCHAR(64)  NOT NULL,
    expires_at     TIMESTAMP    NOT NULL,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    invalidated_at TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Fast lookup by email for verify + rate-limit count queries
CREATE INDEX idx_otp_tokens_email      ON otp_tokens (email);

-- Fast cleanup of expired tokens in nightly jobs
CREATE INDEX idx_otp_tokens_expires_at ON otp_tokens (expires_at);
