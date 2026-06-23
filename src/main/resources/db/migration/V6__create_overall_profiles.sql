-- V6__create_overall_profiles.sql

CREATE TABLE IF NOT EXISTS overall_profiles (
                                  id                          BIGSERIAL PRIMARY KEY,

    -- One profile per user
                                  user_id                     BIGINT NOT NULL UNIQUE
                                      REFERENCES users(id) ON DELETE CASCADE,

    -- Coverage
                                  months_analyzed             INT            NOT NULL DEFAULT 0,
                                  earliest_month              DATE,
                                  latest_month                DATE,

    -- Aggregate scores
                                  avg_health_score            INT            NOT NULL DEFAULT 0,
                                  latest_health_score         INT            NOT NULL DEFAULT 0,
                                  best_health_score           INT            NOT NULL DEFAULT 0,
                                  worst_health_score          INT            NOT NULL DEFAULT 0,

    -- Sub-score averages
                                  avg_savings_rate_score      INT            NOT NULL DEFAULT 0,
                                  avg_spending_discipline_score INT          NOT NULL DEFAULT 0,
                                  avg_debt_burden_score       INT            NOT NULL DEFAULT 0,
                                  avg_income_stability_score  INT            NOT NULL DEFAULT 0,
                                  avg_emergency_cushion_score INT            NOT NULL DEFAULT 0,
                                  avg_goal_alignment_score    INT            NOT NULL DEFAULT 0,

    -- Trend
                                  trend                       VARCHAR(20),
                                  trend_delta                 INT            NOT NULL DEFAULT 0,

    -- Aggregate financials
                                  avg_income                  NUMERIC(15,2),
                                  avg_total_spend             NUMERIC(15,2),
                                  avg_actual_savings          NUMERIC(15,2),
                                  avg_lifestyle_ratio         DOUBLE PRECISION NOT NULL DEFAULT 0,

    -- Income consistency
                                  income_consistency          DOUBLE PRECISION NOT NULL DEFAULT 0,

    -- Best / worst months
                                  best_month                  DATE,
                                  worst_month                 DATE,

    -- Archetype
                                  archetype                   VARCHAR(50),

    -- Recurring burden
                                  confirmed_recurring_total   NUMERIC(15,2),
                                  confirmed_recurring_count   INT            NOT NULL DEFAULT 0,
                                  undeclared_recurring_count  INT            NOT NULL DEFAULT 0,

    -- JSON blobs
                                  monthly_score_history       TEXT,
                                  profile_json                TEXT,

    -- Timestamps
                                  last_refreshed_at           TIMESTAMP      NOT NULL DEFAULT NOW(),
                                  created_at                  TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_overall_profile_user
    ON overall_profiles (user_id);