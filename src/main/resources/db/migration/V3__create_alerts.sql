-- DECIMAL(19,8) — exact arithmetic. Never FLOAT.
--   80000.0 as FLOAT may round to 79999.999... and the alert never fires.
--
-- condition VARCHAR(7): ABOVE | BELOW | CROSSES
--   CROSSES fires when price touches the target from either direction.
--
-- Single status ENUM — no impossible boolean states.
--   is_active=TRUE + is_triggered=TRUE cannot be represented.
--
-- triggered_price — actual market price at fire time, not target_price.
--   Shown in the trigger email so users see exactly what price hit.
--
-- Partial unique index (idx_alerts_unique_active):
--   Prevents duplicate ACTIVE alerts at DB level.
--   WHERE status='ACTIVE' — once triggered, user can re-create the same alert.
--   App layer checks first (returns 409). Index catches race conditions.

CREATE TABLE alerts (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    symbol          VARCHAR(20)    NOT NULL,
    target_price    DECIMAL(19, 8) NOT NULL,
    condition       VARCHAR(7)     NOT NULL
                                   CHECK (condition IN ('ABOVE', 'BELOW', 'CROSSES')),
    status          VARCHAR(10)    NOT NULL DEFAULT 'ACTIVE'
                                   CHECK (status IN ('ACTIVE', 'TRIGGERED')),
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    triggered_at    TIMESTAMP,
    triggered_price DECIMAL(19, 8)
);

-- GET /api/v1/alerts — all alerts for a user
CREATE INDEX idx_alerts_user_id       ON alerts (user_id);

-- BinanceWebSocketService — loads all ACTIVE alerts on connect
CREATE INDEX idx_alerts_status        ON alerts (status);

-- BinanceWebSocketService — per-symbol lookup
CREATE INDEX idx_alerts_symbol_status ON alerts (symbol, status);

-- Duplicate ACTIVE alert prevention at DB level
CREATE UNIQUE INDEX idx_alerts_unique_active
    ON alerts (user_id, symbol, condition, target_price)
    WHERE status = 'ACTIVE';
