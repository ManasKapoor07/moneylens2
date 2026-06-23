CREATE TABLE users
(
    id           BIGSERIAL PRIMARY KEY,
    phone_number VARCHAR(15) UNIQUE NOT NULL,
    verified     BOOLEAN            NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP          NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP          NOT NULL DEFAULT NOW()
);

CREATE TABLE otp_tokens
(
    id           BIGSERIAL PRIMARY KEY,
    phone_number VARCHAR(15) NOT NULL,
    otp          VARCHAR(6)  NOT NULL,
    expires_at   TIMESTAMP   NOT NULL,
    used         BOOLEAN     NOT NULL DEFAULT FALSE,
    attempts     INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_otp_phone ON otp_tokens (phone_number);
CREATE INDEX idx_user_phone ON users (phone_number);
