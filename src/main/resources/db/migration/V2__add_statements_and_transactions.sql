-- V2__add_statements_and_transactions.sql

-- ── Bank Statements (metadata only, no file stored) ───────────────────────────
CREATE TABLE bank_statements (
                                 id                      BIGSERIAL PRIMARY KEY,
                                 user_id                 BIGINT NOT NULL REFERENCES users(id),
                                 bank_name               VARCHAR(20) NOT NULL,
                                 statement_year          INT NOT NULL,
                                 statement_month         INT NOT NULL,
                                 status                  VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
                                 raw_transaction_count   INT,
                                 parsed_transaction_count INT,
                                 opening_balance         NUMERIC(15, 2),
                                 closing_balance         NUMERIC(15, 2),
                                 total_debits            NUMERIC(15, 2),
                                 total_credits           NUMERIC(15, 2),
                                 error_message           TEXT,
                                 uploaded_at             TIMESTAMP NOT NULL DEFAULT NOW(),
                                 parsed_at               TIMESTAMP,

    -- One statement per bank per month per user
                                 CONSTRAINT uq_statement_user_bank_month
                                     UNIQUE (user_id, bank_name, statement_year, statement_month)
);

CREATE INDEX idx_stmt_user ON bank_statements(user_id);

-- ── Transactions ──────────────────────────────────────────────────────────────
CREATE TABLE transactions (
                              id                   BIGSERIAL PRIMARY KEY,
                              user_id              BIGINT NOT NULL REFERENCES users(id),

    -- Raw parsed fields
                              date                 DATE NOT NULL,
                              value_date           DATE,
                              raw_narration        TEXT NOT NULL,
                              reference_number     VARCHAR(20),
                              withdrawal_amount    NUMERIC(15, 2),
                              deposit_amount       NUMERIC(15, 2),
                              closing_balance      NUMERIC(15, 2),
                              type                 VARCHAR(10) NOT NULL,   -- DEBIT / CREDIT

    -- Derived at parse time
                              mode                 VARCHAR(20),            -- UPI / EMI / NEFT / IMPS / AUTOPAY / OTHER
                              upi_handle           VARCHAR(100),
                              counterparty_name    VARCHAR(200),
                              counterparty_phone   VARCHAR(15),
                              is_refund            BOOLEAN DEFAULT FALSE,

    -- Enriched later
                              merchant_name        VARCHAR(200),
                              category             VARCHAR(100),
                              is_recurring         BOOLEAN,

    -- Statement metadata
                              bank_name            VARCHAR(20) NOT NULL,
                              statement_year       INT NOT NULL,
                              statement_month      INT NOT NULL,
                              created_at           TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Dedup: same ref number can't appear twice for same user
                              CONSTRAINT uq_transaction_ref_user
                                  UNIQUE (reference_number, user_id)
);

CREATE INDEX idx_txn_user          ON transactions(user_id);
CREATE INDEX idx_txn_user_month    ON transactions(user_id, statement_year, statement_month);
CREATE INDEX idx_txn_date          ON transactions(user_id, date DESC);
CREATE INDEX idx_txn_category      ON transactions(user_id, category);