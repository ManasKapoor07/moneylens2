CREATE TABLE IF NOT EXISTS financial_goals (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    title         VARCHAR(200) NOT NULL,
    emoji         VARCHAR(10),
    target_amount NUMERIC(15,2) NOT NULL,
    saved_amount  NUMERIC(15,2) NOT NULL DEFAULT 0,
    target_date   DATE,
    frequency     VARCHAR(10)  NOT NULL DEFAULT 'MONTHLY',
    ai_plan       TEXT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_financial_goals_user
    ON financial_goals (user_id);

CREATE INDEX IF NOT EXISTS idx_financial_goals_status
    ON financial_goals (user_id, status);
