-- V7__create_statement_profiles_and_recurring_payments.sql

-- ── 1. statement_profiles ────────────────────────────────────────────────────

CREATE TABLE statement_profiles (
                                    id                          BIGSERIAL PRIMARY KEY,

                                    user_id                     BIGINT          NOT NULL
                                        REFERENCES users(id) ON DELETE CASCADE,
                                    statement_id                BIGINT          NOT NULL
                                        REFERENCES bank_statements(id) ON DELETE CASCADE,

    -- Which calendar month
                                    profile_year                INT             NOT NULL,
                                    profile_month               INT             NOT NULL,

    -- Composite score
                                    health_score                INT             NOT NULL DEFAULT 0,
                                    archetype                   VARCHAR(50),

    -- Sub-scores
                                    savings_rate_score          INT             NOT NULL DEFAULT 0,
                                    spending_discipline_score   INT             NOT NULL DEFAULT 0,
                                    debt_burden_score           INT             NOT NULL DEFAULT 0,
                                    income_stability_score      INT             NOT NULL DEFAULT 0,
                                    emergency_cushion_score     INT             NOT NULL DEFAULT 0,
                                    goal_alignment_score        INT             NOT NULL DEFAULT 0,

    -- Actual financials
                                    actual_income               NUMERIC(15,2),
                                    total_spend                 NUMERIC(15,2),
                                    actual_savings              NUMERIC(15,2),
                                    avg_daily_spend             NUMERIC(15,2),
                                    lifestyle_ratio             DOUBLE PRECISION NOT NULL DEFAULT 0,

    -- Salary runway
                                    salary_day                  INT,
                                    runway_days                 INT,
                                    post_salary_surge           BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Weekly pattern
                                    weekly_pattern              VARCHAR(20),
                                    week1_spend                 NUMERIC(15,2),
                                    week2_spend                 NUMERIC(15,2),
                                    week3_spend                 NUMERIC(15,2),
                                    week4_spend                 NUMERIC(15,2),

    -- Declared vs actual flags
                                    income_discrepancy_found    BOOLEAN         NOT NULL DEFAULT FALSE,
                                    savings_overstated          BOOLEAN         NOT NULL DEFAULT FALSE,
                                    hidden_debt_found           BOOLEAN         NOT NULL DEFAULT FALSE,
                                    actual_net_cash_flow        NUMERIC(15,2),

    -- Misc
                                    transaction_count           INT             NOT NULL DEFAULT 0,
                                    profile_json                TEXT,
                                    created_at                  TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_profile_user_month
    ON statement_profiles (user_id, profile_year, profile_month);

CREATE INDEX idx_profile_statement
    ON statement_profiles (statement_id);


-- ── 2. recurring_payments (depends on statement_profiles) ────────────────────

CREATE TABLE recurring_payments (
                                    id                      BIGSERIAL PRIMARY KEY,

                                    user_id                 BIGINT          NOT NULL
                                        REFERENCES users(id) ON DELETE CASCADE,
                                    statement_profile_id    BIGINT          NOT NULL
                                        REFERENCES statement_profiles(id) ON DELETE CASCADE,

                                    profile_year            INT             NOT NULL,
                                    profile_month           INT             NOT NULL,

                                    merchant                VARCHAR(100)    NOT NULL,
                                    merchant_key            VARCHAR(100)    NOT NULL,

                                    amount                  NUMERIC(15,2)   NOT NULL,
                                    occurrences             INT             NOT NULL DEFAULT 1,

                                    recurring_type          VARCHAR(20)     NOT NULL,
                                    declared_in_assessment  BOOLEAN         NOT NULL DEFAULT FALSE,
                                    confidence              VARCHAR(20)              DEFAULT 'POSSIBLE',
                                    months_detected         INT             NOT NULL DEFAULT 1,

                                    detection_reason        TEXT,
                                    created_at              TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_recurring_user     ON recurring_payments (user_id);
CREATE INDEX idx_recurring_profile  ON recurring_payments (statement_profile_id);
CREATE INDEX idx_recurring_merchant ON recurring_payments (user_id, merchant_key);