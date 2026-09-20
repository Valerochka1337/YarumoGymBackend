--liquibase formatted sql
--changeset codex:029-coach-behavior
ALTER TABLE coach_sessions ADD COLUMN behavior_state JSONB NOT NULL DEFAULT '{}';
ALTER TABLE coach_sessions ADD COLUMN schema_version INT NOT NULL DEFAULT 1;
ALTER TABLE coach_sessions ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE coach_sessions ADD COLUMN policy_version VARCHAR(40) NOT NULL DEFAULT 'behavior-1';
ALTER TABLE coach_sessions ADD COLUMN behavior_mode VARCHAR(16) NOT NULL DEFAULT 'SHADOW';
ALTER TABLE coach_workout_events ALTER COLUMN request_id DROP NOT NULL;
ALTER TABLE coach_workout_events ADD CONSTRAINT coach_workout_events_owner_fk FOREIGN KEY(owner_id) REFERENCES users(id) ON DELETE CASCADE;
CREATE TABLE coach_behavior_events (
 owner_id UUID NOT NULL,
 workout_id UUID NOT NULL,
 event_id UUID NOT NULL,
 state_version BIGINT NOT NULL,
 input JSONB NOT NULL,
 decision JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(owner_id,workout_id,event_id),
 FOREIGN KEY(owner_id,workout_id) REFERENCES coach_sessions(owner_id,workout_id) ON DELETE CASCADE
);
CREATE INDEX coach_behavior_timeline ON coach_behavior_events(owner_id,workout_id,state_version);
