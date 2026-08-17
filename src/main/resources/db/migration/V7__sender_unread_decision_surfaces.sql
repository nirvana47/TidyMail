CREATE TABLE protected_senders (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    sender_address VARCHAR(320) NOT NULL,
    source VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_protected_senders_account_address
        UNIQUE (account_id, sender_address),
    CONSTRAINT protected_senders_address_normalized_check
        CHECK (sender_address <> '' AND sender_address = lower(trim(sender_address))),
    CONSTRAINT protected_senders_source_check
        CHECK (source IN ('MIGRATION', 'USER', 'ACTION'))
);

CREATE INDEX idx_protected_senders_account_created
    ON protected_senders (account_id, created_at DESC);

CREATE TABLE protected_override_audits (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    selection_surface VARCHAR(16) NOT NULL,
    selection_mode VARCHAR(16) NOT NULL,
    action_type VARCHAR(16) NOT NULL,
    query_fingerprint VARCHAR(64) NOT NULL,
    protected_sender_count INTEGER NOT NULL,
    protected_message_count INTEGER NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT protected_override_surface_check
        CHECK (selection_surface IN ('SENDERS', 'UNREAD')),
    CONSTRAINT protected_override_mode_check
        CHECK (selection_mode IN ('INDIVIDUAL', 'VISIBLE_PAGE', 'ALL_MATCHING')),
    CONSTRAINT protected_override_action_check
        CHECK (action_type IN ('ARCHIVE', 'TRASH', 'MARK_READ', 'KEEP', 'REVIEW')),
    CONSTRAINT protected_override_fingerprint_check
        CHECK (query_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT protected_override_sender_count_check
        CHECK (protected_sender_count >= 0),
    CONSTRAINT protected_override_message_count_check
        CHECK (protected_message_count >= 0),
    CONSTRAINT protected_override_reason_check
        CHECK (reason_code IN (
            'FALSE_POSITIVE', 'BUSINESS_REQUIRED', 'USER_CONFIRMED', 'OTHER_REVIEWED'
        ))
);

CREATE INDEX idx_protected_override_audits_account_created
    ON protected_override_audits (account_id, created_at DESC);

CREATE INDEX idx_message_metadata_account_sender_window
    ON message_metadata (account_id, from_address, received_at DESC, id DESC);
CREATE INDEX idx_message_metadata_account_unread_sender_time
    ON message_metadata (account_id, unread, from_address, received_at DESC, id DESC);
