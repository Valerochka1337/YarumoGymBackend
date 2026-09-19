--liquibase formatted sql
--changeset codex:022-coach-session-stream
--validCheckSum: 9:e6359f6df0e93593ba68ddd6229dba2d
ALTER TABLE coach_sessions ADD COLUMN semantic_version VARCHAR(64);
ALTER TABLE coach_sessions ADD COLUMN model VARCHAR(200);
ALTER TABLE coach_sessions ADD COLUMN evaluated_snapshot JSONB;
ALTER TABLE coach_sessions ADD COLUMN evaluated_version VARCHAR(64);
ALTER TABLE coach_run_receipts ADD COLUMN reason VARCHAR(2000);
ALTER TABLE coach_run_receipts ADD COLUMN snapshot JSONB;
ALTER TABLE coach_run_receipts ADD COLUMN request_digest VARCHAR(64);
CREATE TABLE coach_decisions (
 owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 workout_id UUID NOT NULL,
 proposal_id UUID NOT NULL,
 payload JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(owner_id,workout_id,proposal_id)
);
CREATE TABLE coach_workout_event_cursors (
 owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 workout_id UUID NOT NULL,
 sequence BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(owner_id,workout_id)
);
CREATE TABLE coach_workout_events (
 owner_id UUID NOT NULL,
 workout_id UUID NOT NULL,
 sequence BIGINT NOT NULL,
 request_id UUID NOT NULL,
 payload JSONB NOT NULL,
 PRIMARY KEY(owner_id,workout_id,sequence),
 FOREIGN KEY(owner_id,request_id) REFERENCES coach_runs(owner_id,request_id) ON DELETE CASCADE
);
INSERT INTO coach_workout_events
 SELECT owner_id,workout_id,n,request_id,payload || jsonb_build_object('sequence',n,'runId',request_id,'origin',CASE WHEN automatic THEN 'COACH' ELSE 'USER' END)
 FROM (SELECT r.owner_id,r.workout_id,r.request_id,r.automatic,e.payload,
 row_number() OVER(PARTITION BY r.owner_id,r.workout_id ORDER BY r.ordinal,e.sequence) n
 FROM coach_runs r JOIN coach_run_events e USING(owner_id,request_id)) history;
INSERT INTO coach_workout_event_cursors SELECT owner_id,workout_id,max(sequence) FROM coach_workout_events GROUP BY owner_id,workout_id;
