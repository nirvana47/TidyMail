ALTER TABLE sender_units DROP CONSTRAINT sender_units_kind_check;
ALTER TABLE sender_units ADD CHECK (kind IN ('ADDRESS','LIST','MESSAGE','SPLIT_ADDRESS'));

-- Dashboard-only membership for addresses explicitly partitioned within a list.
-- The original message_sender_units links still carry provider identity evidence.
CREATE TABLE sender_address_units (
    account_id bigint NOT NULL,
    source_unit_id bigint NOT NULL,
    address text NOT NULL,
    unit_id bigint NOT NULL UNIQUE,
    active boolean NOT NULL DEFAULT false,
    PRIMARY KEY (account_id, source_unit_id, address),
    FOREIGN KEY (source_unit_id, account_id) REFERENCES sender_units(id, account_id) ON DELETE CASCADE,
    FOREIGN KEY (unit_id, account_id) REFERENCES sender_units(id, account_id) ON DELETE CASCADE
);
