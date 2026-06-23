ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS system_category VARCHAR(30),
    ADD COLUMN IF NOT EXISTS user_category   VARCHAR(30),
    ADD COLUMN IF NOT EXISTS category_source VARCHAR(10);
