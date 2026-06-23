CREATE TABLE manual_expenses (
                                 id BIGSERIAL PRIMARY KEY,
                                 user_id BIGINT NOT NULL REFERENCES users(id),
                                 amount NUMERIC(12,2) NOT NULL,
                                 category VARCHAR(64) NOT NULL,
                                 note VARCHAR(255),
                                 spent_at TIMESTAMP NOT NULL,
                                 created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_manual_expenses_user_month
    ON manual_expenses (user_id, spent_at);