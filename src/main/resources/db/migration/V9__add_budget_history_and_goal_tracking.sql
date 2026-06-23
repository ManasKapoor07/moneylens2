-- V9__add_budget_history_and_goal_tracking.sql

ALTER TABLE budgets
    ADD COLUMN previous_category_budgets JSONB,
    ADD COLUMN previous_total_budget NUMERIC(12,2),
    ADD COLUMN previous_savings_target NUMERIC(12,2),
    ADD COLUMN last_diff_summary TEXT;