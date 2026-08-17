CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE user_actions DROP CONSTRAINT user_actions_status_check;
ALTER TABLE user_actions DROP CONSTRAINT user_actions_action_type_check;
ALTER TABLE user_actions ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE user_actions ALTER COLUMN action_type TYPE VARCHAR(32);
ALTER TABLE user_actions
    ADD CONSTRAINT user_actions_v9_status_check CHECK (status IN (
        'QUEUED', 'BLOCKED', 'EXECUTING', 'APPLIED', 'PARTIAL',
        'VERIFICATION_PENDING', 'FAILED', 'CANCELLED', 'UNDONE',
        'PARTIALLY_UNDONE', 'COMPENSATED'
    ));
ALTER TABLE user_actions
    ADD CONSTRAINT user_actions_v9_action_type_check CHECK (action_type IN (
        'ARCHIVE', 'TRASH', 'MARK_READ', 'KEEP', 'PROTECT', 'REVIEW',
        'UNSUBSCRIBE', 'REMOVE_PROTECTION', 'DELETE', 'UNDO'
    ));
ALTER TABLE user_actions ADD COLUMN public_id UUID DEFAULT gen_random_uuid();
ALTER TABLE user_actions ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'STAGED';
ALTER TABLE user_actions ADD COLUMN decision_source VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE user_actions ADD COLUMN batch_public_id UUID;
ALTER TABLE user_actions ADD COLUMN completed_at TIMESTAMPTZ;
ALTER TABLE user_actions ADD COLUMN undo_eligible_until TIMESTAMPTZ;
UPDATE user_actions SET public_id = gen_random_uuid() WHERE public_id IS NULL;
ALTER TABLE user_actions ALTER COLUMN public_id SET NOT NULL;
ALTER TABLE user_actions
    ADD CONSTRAINT user_actions_execution_mode_check
    CHECK (execution_mode IN ('STAGED', 'IMMEDIATE'));
ALTER TABLE user_actions
    ADD CONSTRAINT user_actions_decision_source_check
    CHECK (decision_source IN ('SENDERS', 'UNREAD', 'UNSUBSCRIBE', 'REVIEW', 'LEGACY'));
ALTER TABLE user_actions
    ADD CONSTRAINT uq_user_actions_public_id UNIQUE (public_id);
ALTER TABLE user_actions
    ADD CONSTRAINT uq_user_actions_id_account UNIQUE (id, account_id);
CREATE INDEX idx_user_actions_account_status_created_v9
    ON user_actions (account_id, status, created_at DESC, id DESC);
CREATE INDEX idx_user_actions_account_type_created_v9
    ON user_actions (account_id, action_type, created_at DESC, id DESC);
CREATE INDEX idx_user_actions_account_completed_v9
    ON user_actions (account_id, completed_at DESC, id DESC);

ALTER TABLE unsubscribe_intent_nonces
    ADD CONSTRAINT uq_unsubscribe_intent_nonces_id_account UNIQUE (id, account_id);

CREATE TABLE action_intents (
    id BIGSERIAL PRIMARY KEY,
    action_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    subject_kind VARCHAR(32) NOT NULL,
    safe_target_label VARCHAR(512) NOT NULL,
    target_search_key VARCHAR(512) NOT NULL,
    normalized_sender_address VARCHAR(320),
    unsubscribe_candidate_fingerprint VARCHAR(64),
    unsubscribe_destination_fingerprint VARCHAR(64),
    unsubscribe_method VARCHAR(24),
    unsubscribe_intent_nonce_id BIGINT,
    compensates_action_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_action_intents_action UNIQUE (action_id),
    CONSTRAINT uq_action_intents_id_account UNIQUE (id, account_id),
    CONSTRAINT fk_action_intents_action_account FOREIGN KEY (action_id, account_id)
        REFERENCES user_actions(id, account_id) ON DELETE CASCADE,
    CONSTRAINT fk_action_intents_nonce_account
        FOREIGN KEY (unsubscribe_intent_nonce_id, account_id)
        REFERENCES unsubscribe_intent_nonces(id, account_id),
    CONSTRAINT fk_action_intents_compensation_account
        FOREIGN KEY (compensates_action_id, account_id)
        REFERENCES user_actions(id, account_id),
    CONSTRAINT action_intents_subject_check CHECK (subject_kind IN (
        'MESSAGES', 'SENDER', 'UNSUBSCRIBE_CANDIDATE', 'LOCAL_REVIEW'
    )),
    CONSTRAINT action_intents_candidate_fingerprint_check CHECK (
        unsubscribe_candidate_fingerprint IS NULL
        OR unsubscribe_candidate_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT action_intents_destination_fingerprint_check CHECK (
        unsubscribe_destination_fingerprint IS NULL
        OR unsubscribe_destination_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT action_intents_method_check CHECK (
        unsubscribe_method IS NULL OR unsubscribe_method IN (
            'RFC8058_POST', 'LEGACY_HTTPS_GET', 'MAILTO', 'ACCOUNT_PAGE', 'MANUAL'
        )
    )
);
CREATE INDEX idx_action_intents_account_search
    ON action_intents (account_id, target_search_key, action_id);

CREATE TABLE action_scope_targets (
    id BIGSERIAL PRIMARY KEY,
    scope_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    scope_ordinal INTEGER NOT NULL,
    target_fingerprint VARCHAR(64) NOT NULL,
    CONSTRAINT uq_action_scope_targets_scope_ordinal UNIQUE (scope_id, scope_ordinal),
    CONSTRAINT uq_action_scope_targets_owned_target
        UNIQUE (scope_id, account_id, scope_ordinal, target_fingerprint),
    CONSTRAINT uq_action_scope_targets_id_account UNIQUE (id, account_id),
    CONSTRAINT fk_action_scope_targets_scope_account
        FOREIGN KEY (scope_id, account_id)
        REFERENCES action_scopes(id, account_id) ON DELETE CASCADE,
    CONSTRAINT action_scope_targets_ordinal_check CHECK (scope_ordinal >= 0),
    CONSTRAINT action_scope_targets_fingerprint_check
        CHECK (target_fingerprint ~ '^[0-9a-f]{64}$')
);
INSERT INTO action_scope_targets (
    scope_id, account_id, scope_ordinal, target_fingerprint
)
SELECT scope.id, scope.account_id, target.ordinality - 1,
       encode(digest(convert_to(target.message_id, 'UTF8'), 'sha256'), 'hex')
FROM action_scopes scope
CROSS JOIN LATERAL unnest(scope.message_ids)
    WITH ORDINALITY AS target(message_id, ordinality);
CREATE INDEX idx_action_scope_targets_account_fingerprint
    ON action_scope_targets (account_id, target_fingerprint, scope_id, scope_ordinal);

CREATE TABLE action_command_tokens (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    token_fingerprint VARCHAR(64) NOT NULL,
    owner_fingerprint VARCHAR(64) NOT NULL,
    operation_fingerprint VARCHAR(64) NOT NULL,
    subject_fingerprint VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    unsubscribe_intent_nonce_id BIGINT,
    resulting_action_id BIGINT,
    resulting_execution_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_action_command_tokens_public UNIQUE (public_id),
    CONSTRAINT uq_action_command_tokens_account_token UNIQUE (account_id, token_fingerprint),
    CONSTRAINT uq_action_command_tokens_id_account UNIQUE (id, account_id),
    CONSTRAINT fk_action_command_tokens_nonce_account
        FOREIGN KEY (unsubscribe_intent_nonce_id, account_id)
        REFERENCES unsubscribe_intent_nonces(id, account_id),
    CONSTRAINT fk_action_command_tokens_action_account
        FOREIGN KEY (resulting_action_id, account_id)
        REFERENCES user_actions(id, account_id) ON DELETE CASCADE,
    CONSTRAINT action_command_tokens_token_check
        CHECK (token_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT action_command_tokens_owner_check
        CHECK (owner_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT action_command_tokens_operation_check
        CHECK (operation_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT action_command_tokens_subject_check
        CHECK (subject_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT action_command_tokens_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT action_command_tokens_consumed_check
        CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX idx_action_command_tokens_account_expiry
    ON action_command_tokens (account_id, expires_at, consumed_at);

CREATE TABLE action_executions (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    account_id BIGINT NOT NULL,
    action_id BIGINT NOT NULL,
    operation_kind VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_fingerprint VARCHAR(64) NOT NULL,
    lease_fingerprint VARCHAR(64),
    lease_expires_at TIMESTAMPTZ,
    requested_count INTEGER NOT NULL,
    accepted_count INTEGER NOT NULL DEFAULT 0,
    succeeded_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    verification_pending_count INTEGER NOT NULL DEFAULT 0,
    not_dispatched_count INTEGER NOT NULL,
    reason_code VARCHAR(48),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_action_executions_public UNIQUE (public_id),
    CONSTRAINT uq_action_executions_id_account UNIQUE (id, account_id),
    CONSTRAINT uq_action_executions_id_action_account
        UNIQUE (id, action_id, account_id),
    CONSTRAINT uq_action_executions_action_operation UNIQUE (action_id, operation_kind),
    CONSTRAINT uq_action_executions_account_idempotency
        UNIQUE (account_id, idempotency_fingerprint),
    CONSTRAINT fk_action_executions_action_account FOREIGN KEY (action_id, account_id)
        REFERENCES user_actions(id, account_id) ON DELETE CASCADE,
    CONSTRAINT action_executions_operation_check
        CHECK (operation_kind IN ('FORWARD', 'UNDO', 'COMPENSATION')),
    CONSTRAINT action_executions_status_check CHECK (status IN (
        'RESERVED', 'RUNNING', 'APPLIED', 'PARTIAL', 'VERIFICATION_PENDING', 'FAILED'
    )),
    CONSTRAINT action_executions_idempotency_check
        CHECK (idempotency_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT action_executions_lease_check
        CHECK (lease_fingerprint IS NULL OR lease_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT action_executions_lease_pair_check
        CHECK ((lease_fingerprint IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT action_executions_counts_check CHECK (
        requested_count > 0
        AND accepted_count BETWEEN 0 AND requested_count
        AND succeeded_count BETWEEN 0 AND requested_count
        AND failed_count BETWEEN 0 AND requested_count
        AND verification_pending_count BETWEEN 0 AND requested_count
        AND not_dispatched_count BETWEEN 0 AND requested_count
        AND requested_count = succeeded_count + failed_count
            + verification_pending_count + not_dispatched_count
    ),
    CONSTRAINT action_executions_reason_check CHECK (
        reason_code IS NULL OR reason_code IN (
            'NONE', 'PROVIDER_REJECTED', 'PROVIDER_UNAVAILABLE',
            'PROVIDER_RESULT_INVALID', 'RESULT_UNKNOWN_AFTER_DISPATCH',
            'NOT_DISPATCHED', 'STALE_ACTION', 'CONFLICT', 'PROTECTED_TARGET',
            'FRESH_CONSENT_REQUIRED', 'LEASE_EXPIRED'
        )
    )
);
ALTER TABLE action_command_tokens
    ADD CONSTRAINT fk_action_command_tokens_execution_action_account
    FOREIGN KEY (resulting_execution_id, resulting_action_id, account_id)
    REFERENCES action_executions(id, action_id, account_id) ON DELETE CASCADE;
CREATE INDEX idx_action_executions_account_status_started
    ON action_executions (account_id, status, started_at DESC, id DESC);
CREATE INDEX idx_action_executions_action_time
    ON action_executions (action_id, reserved_at, id);

CREATE TABLE action_execution_targets (
    id BIGSERIAL PRIMARY KEY,
    execution_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    scope_id BIGINT NOT NULL,
    scope_ordinal INTEGER NOT NULL,
    target_fingerprint VARCHAR(64) NOT NULL,
    dispatch_state VARCHAR(16) NOT NULL DEFAULT 'NOT_DISPATCHED',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    accepted_at TIMESTAMPTZ,
    dispatched_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    reason_code VARCHAR(48),
    unsubscribe_attempt_id BIGINT,
    CONSTRAINT uq_action_execution_targets_execution_ordinal
        UNIQUE (execution_id, scope_ordinal),
    CONSTRAINT uq_action_execution_targets_id_account UNIQUE (id, account_id),
    CONSTRAINT fk_action_execution_targets_execution_account
        FOREIGN KEY (execution_id, account_id)
        REFERENCES action_executions(id, account_id) ON DELETE CASCADE,
    CONSTRAINT fk_action_execution_targets_scope_target
        FOREIGN KEY (scope_id, account_id, scope_ordinal, target_fingerprint)
        REFERENCES action_scope_targets(
            scope_id, account_id, scope_ordinal, target_fingerprint
        ),
    CONSTRAINT fk_action_execution_targets_unsubscribe_attempt
        FOREIGN KEY (unsubscribe_attempt_id, account_id)
        REFERENCES unsubscribe_attempts(id, account_id),
    CONSTRAINT action_execution_targets_dispatch_check
        CHECK (dispatch_state IN ('NOT_DISPATCHED', 'DISPATCHED')),
    CONSTRAINT action_execution_targets_status_check CHECK (status IN (
        'PENDING', 'SUCCEEDED', 'FAILED', 'VERIFICATION_PENDING',
        'REVERTED', 'COMPENSATED'
    )),
    CONSTRAINT action_execution_targets_fingerprint_check
        CHECK (target_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT action_execution_targets_reason_check CHECK (
        reason_code IS NULL OR reason_code IN (
            'NONE', 'PROVIDER_REJECTED', 'PROVIDER_UNAVAILABLE',
            'PROVIDER_RESULT_INVALID', 'RESULT_UNKNOWN_AFTER_DISPATCH',
            'NOT_DISPATCHED', 'STALE_ACTION', 'CONFLICT', 'PROTECTED_TARGET',
            'FRESH_CONSENT_REQUIRED', 'LEASE_EXPIRED'
        )
    )
);
CREATE INDEX idx_action_execution_targets_execution_status
    ON action_execution_targets (execution_id, status, scope_ordinal);

CREATE TABLE action_events (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    action_id BIGINT NOT NULL,
    execution_id BIGINT,
    event_type VARCHAR(32) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    execution_mode VARCHAR(16) NOT NULL,
    decision_source VARCHAR(16) NOT NULL,
    resulting_status VARCHAR(32) NOT NULL,
    requested_count INTEGER NOT NULL DEFAULT 0,
    accepted_count INTEGER NOT NULL DEFAULT 0,
    succeeded_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    verification_pending_count INTEGER NOT NULL DEFAULT 0,
    not_dispatched_count INTEGER NOT NULL DEFAULT 0,
    reason_code VARCHAR(48),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_action_events_id_account UNIQUE (id, account_id),
    CONSTRAINT fk_action_events_action_account FOREIGN KEY (action_id, account_id)
        REFERENCES user_actions(id, account_id) ON DELETE CASCADE,
    CONSTRAINT fk_action_events_execution_action_account
        FOREIGN KEY (execution_id, action_id, account_id)
        REFERENCES action_executions(id, action_id, account_id) ON DELETE CASCADE,
    CONSTRAINT action_events_type_check CHECK (event_type IN (
        'ACTION_QUEUED', 'EXECUTION_RESERVED', 'EXECUTION_STARTED',
        'EXECUTION_COMPLETED', 'ACTION_CANCELLED', 'UNDO_RESERVED',
        'UNDO_COMPLETED', 'COMPENSATION_CREATED'
    )),
    CONSTRAINT action_events_action_type_check CHECK (action_type IN (
        'ARCHIVE', 'TRASH', 'MARK_READ', 'KEEP', 'PROTECT', 'REVIEW',
        'UNSUBSCRIBE', 'REMOVE_PROTECTION', 'DELETE', 'UNDO'
    )),
    CONSTRAINT action_events_execution_mode_check
        CHECK (execution_mode IN ('STAGED', 'IMMEDIATE')),
    CONSTRAINT action_events_decision_source_check
        CHECK (decision_source IN (
            'SENDERS', 'UNREAD', 'UNSUBSCRIBE', 'REVIEW', 'LEGACY'
        )),
    CONSTRAINT action_events_resulting_status_check CHECK (resulting_status IN (
        'QUEUED', 'BLOCKED', 'EXECUTING', 'APPLIED', 'PARTIAL',
        'VERIFICATION_PENDING', 'FAILED', 'CANCELLED', 'UNDONE',
        'PARTIALLY_UNDONE', 'COMPENSATED'
    )),
    CONSTRAINT action_events_reason_check CHECK (
        reason_code IS NULL OR reason_code IN (
            'NONE', 'PROVIDER_REJECTED', 'PROVIDER_UNAVAILABLE',
            'PROVIDER_RESULT_INVALID', 'RESULT_UNKNOWN_AFTER_DISPATCH',
            'NOT_DISPATCHED', 'STALE_ACTION', 'CONFLICT', 'PROTECTED_TARGET',
            'FRESH_CONSENT_REQUIRED', 'LEASE_EXPIRED'
        )
    ),
    CONSTRAINT action_events_counts_check CHECK (
        requested_count >= 0
        AND accepted_count BETWEEN 0 AND requested_count
        AND succeeded_count BETWEEN 0 AND requested_count
        AND failed_count BETWEEN 0 AND requested_count
        AND verification_pending_count BETWEEN 0 AND requested_count
        AND not_dispatched_count BETWEEN 0 AND requested_count
        AND requested_count = succeeded_count + failed_count
            + verification_pending_count + not_dispatched_count
    )
);
CREATE INDEX idx_action_events_account_time
    ON action_events (account_id, occurred_at DESC, id DESC);
CREATE INDEX idx_action_events_action_time
    ON action_events (action_id, occurred_at, id);
CREATE INDEX idx_action_events_account_type_time
    ON action_events (account_id, action_type, occurred_at DESC, id DESC);
CREATE INDEX idx_action_events_account_status_time
    ON action_events (account_id, resulting_status, occurred_at DESC, id DESC);

CREATE TABLE action_conflicts (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    account_id BIGINT NOT NULL,
    lower_action_id BIGINT NOT NULL,
    higher_action_id BIGINT NOT NULL,
    reason_code VARCHAR(48) NOT NULL,
    overlap_count INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    resolution_code VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT uq_action_conflicts_public UNIQUE (public_id),
    CONSTRAINT uq_action_conflicts_pair UNIQUE (
        account_id, lower_action_id, higher_action_id
    ),
    CONSTRAINT uq_action_conflicts_id_account UNIQUE (id, account_id),
    CONSTRAINT fk_action_conflicts_lower_account
        FOREIGN KEY (lower_action_id, account_id)
        REFERENCES user_actions(id, account_id) ON DELETE CASCADE,
    CONSTRAINT fk_action_conflicts_higher_account
        FOREIGN KEY (higher_action_id, account_id)
        REFERENCES user_actions(id, account_id) ON DELETE CASCADE,
    CONSTRAINT action_conflicts_pair_order_check CHECK (lower_action_id < higher_action_id),
    CONSTRAINT action_conflicts_overlap_check CHECK (overlap_count > 0),
    CONSTRAINT action_conflicts_status_check CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT action_conflicts_reason_check CHECK (reason_code IN (
        'INCOMPATIBLE_ACTIONS', 'REVIEW_BLOCKS_EXECUTION', 'DUPLICATE_ACTION'
    )),
    CONSTRAINT action_conflicts_resolution_check CHECK (
        resolution_code IS NULL OR resolution_code IN (
            'LOWER_CANCELLED', 'HIGHER_CANCELLED', 'KEEP_BOTH_COMPATIBLE'
        )
    )
);
CREATE INDEX idx_action_conflicts_account_status_created
    ON action_conflicts (account_id, status, created_at DESC, id DESC);
