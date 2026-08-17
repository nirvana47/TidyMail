ALTER TABLE message_metadata
    ADD COLUMN size_estimate BIGINT;
ALTER TABLE message_metadata
    ADD COLUMN has_attachments BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE message_metadata
    ADD COLUMN metadata_snippet VARCHAR(512);
ALTER TABLE message_metadata
    ADD CONSTRAINT message_metadata_size_estimate_check
    CHECK (size_estimate IS NULL OR size_estimate >= 0);

CREATE TABLE sender_categories (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    name_key VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sender_categories_id_account UNIQUE (id, account_id),
    CONSTRAINT uq_sender_categories_account_name UNIQUE (account_id, name_key),
    CONSTRAINT sender_categories_name_check CHECK (
        name <> '' AND name = trim(name) AND char_length(name) <= 80
    ),
    CONSTRAINT sender_categories_name_key_check CHECK (
        name_key <> '' AND name_key = lower(trim(name_key))
        AND char_length(name_key) <= 80
    )
);

CREATE TABLE sender_category_assignments (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    sender_address VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_sender_category_assignments_category
        FOREIGN KEY (category_id, account_id)
        REFERENCES sender_categories(id, account_id) ON DELETE CASCADE,
    CONSTRAINT uq_sender_category_assignments_account_sender
        UNIQUE (account_id, sender_address),
    CONSTRAINT sender_category_assignments_address_check CHECK (
        sender_address <> ''
        AND sender_address = lower(trim(sender_address))
        AND char_length(sender_address) <= 320
    )
);

CREATE INDEX idx_sender_categories_account_created
    ON sender_categories (account_id, created_at, id);
CREATE INDEX idx_sender_category_assignments_category_sender
    ON sender_category_assignments (category_id, sender_address);
