BEGIN;

-- One password credential per user. Only a PBKDF2 hash is stored, never the password.
CREATE TABLE user_credential (
    user_id BIGINT PRIMARY KEY REFERENCES app_user(id),
    password_hash TEXT NOT NULL CHECK (password_hash LIKE 'pbkdf2-sha256$%'),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Server-side sessions. The client keeps a random bearer token; the database keeps only
-- its SHA-256 digest, so a leaked table cannot be replayed as live sessions.
CREATE TABLE user_session (
    token_hash CHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);

CREATE INDEX idx_user_session_active_user ON user_session (user_id) WHERE revoked_at IS NULL;

COMMIT;
