-- V5__bank_statement_date_range.sql

-- 1. Drop old unique index if it exists
DROP INDEX IF EXISTS idx_stmt_user_period;

-- 2. Drop old year/month columns if they exist
ALTER TABLE bank_statements
DROP COLUMN IF EXISTS statement_year,
    DROP COLUMN IF EXISTS statement_month;

-- 3. Add new date range columns (nullable first so existing rows don't fail)
ALTER TABLE bank_statements
    ADD COLUMN IF NOT EXISTS statement_from_date DATE,
    ADD COLUMN IF NOT EXISTS statement_to_date   DATE;

-- 4. Backfill any existing rows so NOT NULL doesn't fail
--    (safe default — update manually if you have real data worth preserving)
UPDATE bank_statements
SET statement_from_date = '2024-01-01',
    statement_to_date   = '2024-01-31'
WHERE statement_from_date IS NULL
   OR statement_to_date   IS NULL;

-- 5. Now enforce NOT NULL
ALTER TABLE bank_statements
    ALTER COLUMN statement_from_date SET NOT NULL,
ALTER COLUMN statement_to_date   SET NOT NULL;

-- 6. Recreate unique index on new columns
CREATE UNIQUE INDEX IF NOT EXISTS idx_stmt_user_period
    ON bank_statements (user_id, bank_name, statement_from_date, statement_to_date);