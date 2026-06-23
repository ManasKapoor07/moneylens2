-- V8__create_budgets.sql
CREATE TABLE budgets (
                         id BIGSERIAL PRIMARY KEY,
                         user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                         total_budget NUMERIC(12,2) NOT NULL,
                         savings_target NUMERIC(12,2),
                         category_budgets JSONB NOT NULL,
                         reasoning JSONB,
                         source VARCHAR(20) NOT NULL DEFAULT 'AUTO',
                         generated_at TIMESTAMP NOT NULL DEFAULT now(),
                         updated_at TIMESTAMP NOT NULL DEFAULT now(),
                         UNIQUE(user_id)
);

CREATE INDEX idx_budgets_user_id ON budgets(user_id);