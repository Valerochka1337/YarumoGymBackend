--liquibase formatted sql

--changeset codex:019-routine-shares
CREATE TABLE routine_shares (
    id UUID PRIMARY KEY,
    -- The immutable snapshot and recipient receipt outlive an author account. Deletion revokes
    -- the share in AuthService, then this reference becomes NULL without deleting either.
    author_id UUID REFERENCES users(id) ON DELETE SET NULL,
    source_routine_id UUID NOT NULL,
    token_digest VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(200) NOT NULL,
    estimated_duration_seconds BIGINT NOT NULL CHECK (estimated_duration_seconds >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX routine_shares_author_active
  ON routine_shares(author_id, source_routine_id, created_at DESC) WHERE revoked_at IS NULL;

CREATE TABLE routine_share_exercises (
    share_id UUID NOT NULL REFERENCES routine_shares(id) ON DELETE RESTRICT,
    position INT NOT NULL CHECK (position >= 0),
    exercise_key UUID NOT NULL,
    standard_exercise_id UUID,
    name VARCHAR(200) NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('STRENGTH','TIMED','CARDIO')),
    custom_muscle_group VARCHAR(16),
    rest_seconds INT NOT NULL CHECK (rest_seconds BETWEEN 0 AND 86400),
    PRIMARY KEY(share_id, position),
    CHECK ((standard_exercise_id IS NULL) <> (custom_muscle_group IS NULL))
);

CREATE TABLE routine_share_sets (
    share_id UUID NOT NULL,
    exercise_position INT NOT NULL,
    set_position INT NOT NULL CHECK (set_position >= 0),
    weight_kg DOUBLE PRECISION,
    reps INT,
    duration_sec INT,
    speed_kmh DOUBLE PRECISION,
    incline_pct DOUBLE PRECISION,
    PRIMARY KEY(share_id, exercise_position, set_position),
    FOREIGN KEY(share_id, exercise_position)
      REFERENCES routine_share_exercises(share_id, position) ON DELETE RESTRICT
);

CREATE TABLE routine_share_operations (
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    share_id UUID NOT NULL REFERENCES routine_shares(id) ON DELETE RESTRICT,
    request_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(author_id, operation_id)
);
CREATE UNIQUE INDEX routine_share_operations_share ON routine_share_operations(share_id);

CREATE TABLE routine_share_import_receipts (
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    share_id UUID NOT NULL REFERENCES routine_shares(id) ON DELETE RESTRICT,
    routine_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    imported_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(recipient_id, share_id),
    UNIQUE(recipient_id, routine_id)
);

CREATE TABLE routine_share_revoke_operations (
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    share_id UUID NOT NULL REFERENCES routine_shares(id) ON DELETE RESTRICT,
    revoked_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(author_id, operation_id)
);
