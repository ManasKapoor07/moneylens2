-- V10__add_budget_category_buckets.sql
-- Stores explicit Needs/Wants bucket assignment for every budget category.
-- Removes dependency on re-resolving display names to the Category enum at read time,
-- so custom user-defined categories can also carry a correct bucket label.
ALTER TABLE budgets
    ADD COLUMN category_buckets JSONB;
