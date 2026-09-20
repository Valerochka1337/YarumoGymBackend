--liquibase formatted sql
--changeset codex:030-coach-interventions
CREATE TABLE coach_questions (
 owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 workout_id UUID NOT NULL,
 question_id UUID NOT NULL,
 topic_key VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL CHECK(status IN ('OPEN','ANSWERED','STALE','EXPIRED')),
 set_id UUID NOT NULL,
 base_revision BIGINT NOT NULL,
 dependencies VARCHAR(64) NOT NULL,
 expires_at BIGINT NOT NULL,
 payload JSONB NOT NULL,
 PRIMARY KEY(owner_id,question_id),
 UNIQUE(owner_id,workout_id,topic_key)
);
CREATE TABLE coach_intervention_proposals (
 owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 workout_id UUID NOT NULL,
 proposal_id UUID NOT NULL,
 intervention_key VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('PRESENTED','APPLIED','REJECTED','STALE','EXPIRED')),
 base_revision BIGINT NOT NULL,
 dependencies VARCHAR(64) NOT NULL,
 expires_at BIGINT NOT NULL,
 payload JSONB NOT NULL,
 PRIMARY KEY(owner_id,proposal_id),
 UNIQUE(owner_id,workout_id,intervention_key)
);
CREATE TABLE coach_intervention_receipts (
 owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 event_id UUID NOT NULL,
 request_digest VARCHAR(64) NOT NULL,
 response JSONB NOT NULL,
 PRIMARY KEY(owner_id,event_id)
);
CREATE INDEX coach_open_questions ON coach_questions(owner_id,workout_id,status);
CREATE INDEX coach_open_interventions ON coach_intervention_proposals(owner_id,workout_id,status);
