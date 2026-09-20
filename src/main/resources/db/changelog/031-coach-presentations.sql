--liquibase formatted sql
--changeset codex:031-coach-presentations
CREATE TABLE coach_presentations (
 id UUID PRIMARY KEY,
 ordinal BIGSERIAL NOT NULL,
 owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 workout_id UUID NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 payload JSONB NOT NULL,
 context_version VARCHAR(64) NOT NULL,
 delivered BOOLEAN NOT NULL DEFAULT false,
 lease_token UUID,
 lease_until TIMESTAMPTZ,
 FOREIGN KEY(owner_id,workout_id) REFERENCES coach_sessions(owner_id,workout_id) ON DELETE CASCADE
);
CREATE INDEX coach_pending_presentations ON coach_presentations(ordinal) WHERE NOT delivered;
