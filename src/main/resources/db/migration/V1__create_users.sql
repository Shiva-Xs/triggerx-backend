-- Email is identity. No name, no phone, no password.
-- UUID PK — not guessable, prevents sequential ID probing.
-- User row is created silently on first successful OTP verify.

CREATE TABLE users (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);
