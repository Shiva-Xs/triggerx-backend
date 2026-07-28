-- token_version is the revocation lever for JWT sessions.
--   Every issued JWT embeds the user's current token_version as a "tv" claim.
--   JwtFilter rejects any token whose "tv" no longer matches this column.
--   POST /api/v1/auth/logout-all increments it, instantly invalidating every
--   token that user has ever been issued — including ones minted through the
--   extension refresh flow — without touching any other user's sessions.

ALTER TABLE users
    ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
