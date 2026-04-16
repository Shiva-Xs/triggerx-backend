-- Links a Telegram chatId to a TriggerX userId.
--
-- One row per linked user. BIGINT because Telegram chat IDs can exceed Integer range.
--
-- Both UNIQUE constraints enforced:
--   chat_id UNIQUE   — one TriggerX user per Telegram chat
--   user_id UNIQUE   — one Telegram chat per TriggerX user
--   Without both, a user could link from two Telegram accounts, causing
--   TelegramUserRepository.findByUserId() to throw IncorrectResultSizeDataAccessException.

CREATE TABLE telegram_users (
    id        UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id   BIGINT    NOT NULL UNIQUE,
    user_id   UUID      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    linked_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_telegram_users_user_id UNIQUE (user_id)
);

-- Fast lookup by chatId on every incoming Telegram message
CREATE INDEX idx_telegram_users_chat_id ON telegram_users (chat_id);

-- Fast lookup by userId when an alert fires (find chatId for notification)
CREATE INDEX idx_telegram_users_user_id ON telegram_users (user_id);
