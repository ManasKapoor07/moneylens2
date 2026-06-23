ALTER TABLE user_assessments
    ADD COLUMN declared_current_income NUMERIC(12,2),
    ADD COLUMN declared_income_updated_at TIMESTAMP;