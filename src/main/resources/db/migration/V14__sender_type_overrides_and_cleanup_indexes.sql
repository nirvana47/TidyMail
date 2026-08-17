CREATE TABLE sender_type_overrides (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    sender_address VARCHAR(320) NOT NULL,
    sender_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sender_type_overrides_account_sender
        UNIQUE (account_id, sender_address),
    CONSTRAINT sender_type_overrides_address_check CHECK (
        sender_address <> ''
        AND sender_address = lower(trim(sender_address))
        AND char_length(sender_address) <= 320
    ),
    CONSTRAINT sender_type_overrides_type_check CHECK (
        sender_type IN ('LIST', 'AUTOMATED', 'TRANSACTIONAL', 'PERSONAL')
    )
);

CREATE INDEX idx_sender_type_overrides_account_type_sender
    ON sender_type_overrides (account_id, sender_type, sender_address);
