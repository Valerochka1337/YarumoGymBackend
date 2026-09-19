--liquibase formatted sql

--changeset codex:024-routine-share-trial-results
CREATE TABLE routine_share_trial_receipts (
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    share_id UUID NOT NULL REFERENCES routine_shares(id) ON DELETE RESTRICT,
    request_sha256 VARCHAR(64) NOT NULL,
    routine_id UUID NOT NULL,
    workout_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    saved_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(recipient_id, operation_id),
    UNIQUE(recipient_id, routine_id),
    UNIQUE(recipient_id, workout_id)
);
CREATE INDEX routine_share_trial_receipts_share ON routine_share_trial_receipts(share_id);
