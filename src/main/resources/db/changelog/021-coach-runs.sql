--liquibase formatted sql
--changeset codex:021-coach-runs
CREATE TABLE coach_sessions (
 owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 workout_id UUID NOT NULL,
 sequence BIGINT NOT NULL DEFAULT 0,
 context_version VARCHAR(64) NOT NULL,
 snapshot JSONB NOT NULL,
 initiative_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 timer_version VARCHAR(64),
 PRIMARY KEY(owner_id,workout_id)
);
CREATE TABLE coach_session_events (
 owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 event_id UUID NOT NULL,
 workout_id UUID NOT NULL,
 request_digest CHAR(64) NOT NULL,
 PRIMARY KEY(owner_id,event_id)
);
CREATE TABLE coach_runs (
 owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 request_id UUID NOT NULL,
 workout_id UUID NOT NULL,
 ordinal BIGSERIAL NOT NULL,
 context_version VARCHAR(64) NOT NULL,
 request_digest CHAR(64) NOT NULL,
 input JSONB NOT NULL,
 automatic BOOLEAN NOT NULL DEFAULT FALSE,
 state VARCHAR(16) NOT NULL CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','SUPERSEDED')),
 stage VARCHAR(80) NOT NULL DEFAULT 'queued',
 checkpoint JSONB,
 result JSONB,
 error_code VARCHAR(64),
 lease_token UUID,
 lease_until TIMESTAMPTZ,
 executions INT NOT NULL DEFAULT 0,
 last_event_sequence BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(owner_id,request_id)
);
CREATE INDEX coach_runs_queue ON coach_runs(state,ordinal);
CREATE INDEX coach_runs_workout ON coach_runs(owner_id,workout_id,ordinal);
CREATE TABLE coach_run_events (
 owner_id UUID NOT NULL,
 request_id UUID NOT NULL,
 sequence BIGINT NOT NULL,
 payload JSONB NOT NULL,
 PRIMARY KEY(owner_id,request_id,sequence),
 FOREIGN KEY(owner_id,request_id) REFERENCES coach_runs(owner_id,request_id) ON DELETE CASCADE
);
CREATE TABLE coach_run_receipts (
 owner_id UUID NOT NULL,
 request_id UUID NOT NULL,
 receipt_id UUID NOT NULL,
 proposal_id UUID NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('APPLIED','REJECTED','STALE')),
 PRIMARY KEY(owner_id,receipt_id),
 FOREIGN KEY(owner_id,request_id) REFERENCES coach_runs(owner_id,request_id) ON DELETE CASCADE
);
