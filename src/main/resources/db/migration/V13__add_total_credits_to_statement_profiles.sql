ALTER TABLE statement_profiles
    ADD COLUMN IF NOT EXISTS total_credits NUMERIC(15, 2);
