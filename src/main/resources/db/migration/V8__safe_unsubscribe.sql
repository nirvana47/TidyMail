ALTER TABLE message_metadata
    ADD COLUMN unsubscribe_dkim_pass BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE message_metadata
    ADD COLUMN unsubscribe_dkim_covers_list_unsubscribe BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE message_metadata
    ADD COLUMN unsubscribe_dkim_covers_list_unsubscribe_post BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE message_metadata
    ADD COLUMN unsubscribe_auth_reason VARCHAR(32);
ALTER TABLE message_metadata
    ADD CONSTRAINT message_metadata_unsubscribe_auth_reason_check
    CHECK (unsubscribe_auth_reason IS NULL OR unsubscribe_auth_reason IN (
        'TRUSTED_PASS', 'NO_TRUSTED_RESULT', 'AMBIGUOUS_RESULT', 'COVERAGE_MISSING'
    ));

CREATE INDEX idx_message_metadata_unsubscribe_candidate
    ON message_metadata (account_id, from_address, received_at DESC, id DESC);
CREATE INDEX idx_message_metadata_unsubscribe_list_source
    ON message_metadata (account_id, received_at DESC, id DESC)
    WHERE cardinality(list_id_values) > 0 OR cardinality(list_unsubscribe_values) > 0;

CREATE TABLE unsubscribe_attempts (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    candidate_fingerprint VARCHAR(64) NOT NULL,
    sender_key_fingerprint VARCHAR(64) NOT NULL,
    method VARCHAR(24) NOT NULL,
    destination_host VARCHAR(253),
    destination_fingerprint VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    latest_status VARCHAR(24) NOT NULL,
    http_status_class SMALLINT,
    reason_code VARCHAR(48),
    confirmation_source VARCHAR(24),
    CONSTRAINT uq_unsubscribe_attempts_id_account UNIQUE (id, account_id),
    CONSTRAINT unsubscribe_attempt_candidate_fingerprint_check
        CHECK (candidate_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT unsubscribe_attempt_sender_fingerprint_check
        CHECK (sender_key_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT unsubscribe_attempt_destination_fingerprint_check
        CHECK (destination_fingerprint IS NULL
            OR destination_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT unsubscribe_attempt_method_check
        CHECK (method IN (
            'RFC8058_POST', 'LEGACY_HTTPS_GET', 'MAILTO', 'ACCOUNT_PAGE', 'MANUAL'
        )),
    CONSTRAINT unsubscribe_attempt_status_check
        CHECK (latest_status IN (
            'PENDING', 'ACCEPTED', 'CONFIRMED', 'FAILED',
            'MANUAL_REQUIRED', 'VERIFICATION_PENDING'
        )),
    CONSTRAINT unsubscribe_attempt_http_status_check
        CHECK (http_status_class IS NULL OR http_status_class BETWEEN 2 AND 5),
    CONSTRAINT unsubscribe_attempt_reason_check
        CHECK (reason_code IS NULL OR reason_code IN (
            'NONE', 'NO_SAFE_METHOD', 'PROTECTED_SENDER', 'MALFORMED_HEADER',
            'UNTRUSTED_AUTH', 'COVERAGE_MISSING', 'DESTINATION_BLOCKED',
            'REDIRECT_BLOCKED', 'TIMEOUT_UNKNOWN_REMOTE_RESULT',
            'NON_SUCCESS_RESPONSE', 'TRANSPORT_ERROR', 'STALE_INTENT',
            'EXPIRED_INTENT', 'REPLAYED_INTENT', 'USER_COMPLETED'
        )),
    CONSTRAINT unsubscribe_attempt_confirmation_source_check
        CHECK (confirmation_source IS NULL OR confirmation_source IN (
            'ENDPOINT_ACCEPTED', 'USER_CONFIRMED', 'PROVIDER_CONFIRMED'
        )),
    CONSTRAINT unsubscribe_attempt_host_check
        CHECK (destination_host IS NULL OR (
            destination_host <> ''
            AND destination_host = lower(trim(destination_host))
            AND destination_host !~ '[/?#@]'
        )),
    CONSTRAINT unsubscribe_attempt_completion_check
        CHECK (completed_at IS NULL OR completed_at >= created_at)
);

CREATE INDEX idx_unsubscribe_attempts_account_status_created
    ON unsubscribe_attempts (account_id, latest_status, created_at DESC, id DESC);
CREATE INDEX idx_unsubscribe_attempts_account_candidate_created
    ON unsubscribe_attempts (account_id, candidate_fingerprint, created_at DESC, id DESC);

CREATE TABLE unsubscribe_outcome_events (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    attempt_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason_code VARCHAR(48),
    http_status_class SMALLINT,
    confirmation_source VARCHAR(24),
    CONSTRAINT fk_unsubscribe_events_attempt_account
        FOREIGN KEY (attempt_id, account_id)
        REFERENCES unsubscribe_attempts(id, account_id) ON DELETE CASCADE,
    CONSTRAINT unsubscribe_event_status_check
        CHECK (status IN (
            'PENDING', 'ACCEPTED', 'CONFIRMED', 'FAILED',
            'MANUAL_REQUIRED', 'VERIFICATION_PENDING'
        )),
    CONSTRAINT unsubscribe_event_http_status_check
        CHECK (http_status_class IS NULL OR http_status_class BETWEEN 2 AND 5),
    CONSTRAINT unsubscribe_event_reason_check
        CHECK (reason_code IS NULL OR reason_code IN (
            'NONE', 'NO_SAFE_METHOD', 'PROTECTED_SENDER', 'MALFORMED_HEADER',
            'UNTRUSTED_AUTH', 'COVERAGE_MISSING', 'DESTINATION_BLOCKED',
            'REDIRECT_BLOCKED', 'TIMEOUT_UNKNOWN_REMOTE_RESULT',
            'NON_SUCCESS_RESPONSE', 'TRANSPORT_ERROR', 'STALE_INTENT',
            'EXPIRED_INTENT', 'REPLAYED_INTENT', 'USER_COMPLETED'
        )),
    CONSTRAINT unsubscribe_event_confirmation_source_check
        CHECK (confirmation_source IS NULL OR confirmation_source IN (
            'ENDPOINT_ACCEPTED', 'USER_CONFIRMED', 'PROVIDER_CONFIRMED'
        ))
);

CREATE INDEX idx_unsubscribe_outcome_events_attempt_time
    ON unsubscribe_outcome_events (attempt_id, occurred_at, id);
CREATE INDEX idx_unsubscribe_outcome_events_account_time
    ON unsubscribe_outcome_events (account_id, occurred_at DESC, id DESC);

CREATE TABLE unsubscribe_intent_nonces (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    nonce_fingerprint VARCHAR(64) NOT NULL,
    candidate_fingerprint VARCHAR(64) NOT NULL,
    destination_fingerprint VARCHAR(64) NOT NULL,
    method VARCHAR(24) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT uq_unsubscribe_intent_nonce
        UNIQUE (account_id, nonce_fingerprint),
    CONSTRAINT unsubscribe_nonce_fingerprint_check
        CHECK (nonce_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT unsubscribe_nonce_candidate_fingerprint_check
        CHECK (candidate_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT unsubscribe_nonce_destination_fingerprint_check
        CHECK (destination_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT unsubscribe_nonce_method_check
        CHECK (method IN (
            'RFC8058_POST', 'LEGACY_HTTPS_GET', 'MAILTO', 'ACCOUNT_PAGE', 'MANUAL'
        )),
    CONSTRAINT unsubscribe_nonce_expiry_check
        CHECK (expires_at > issued_at),
    CONSTRAINT unsubscribe_nonce_consumed_check
        CHECK (consumed_at IS NULL OR consumed_at >= issued_at)
);

CREATE INDEX idx_unsubscribe_intent_nonces_account_expiry
    ON unsubscribe_intent_nonces (account_id, expires_at, consumed_at);
