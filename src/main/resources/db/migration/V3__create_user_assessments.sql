CREATE TABLE IF NOT EXISTS user_assessments (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,

    full_name           VARCHAR(100),
    occupation          VARCHAR(50),
    occupation_detail   VARCHAR(100),
    income_source       VARCHAR(50),
    monthly_income      NUMERIC(12,2),
    monthly_savings     NUMERIC(12,2),
    pay_frequency       VARCHAR(20),
    has_debt            BOOLEAN,
    financial_goal      VARCHAR(100),
    goal_amount         NUMERIC(12,2),
    goal_deadline       TIMESTAMP,
    expense_tracking    VARCHAR(50),
    retirement_age      INT,
    dependents          INT,
    finance_sentiment   VARCHAR(50),
    has_emergency_fund  BOOLEAN,
    emergency_months    INT,
    spending_behaviour  VARCHAR(50),

    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS assessment_app_purposes (
    assessment_id   BIGINT NOT NULL REFERENCES user_assessments(id) ON DELETE CASCADE,
    purpose         VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS assessment_spending_categories (
    assessment_id   BIGINT NOT NULL REFERENCES user_assessments(id) ON DELETE CASCADE,
    category        VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS assessment_financial_challenges (
    assessment_id   BIGINT NOT NULL REFERENCES user_assessments(id) ON DELETE CASCADE,
    challenge       VARCHAR(100) NOT NULL
);
