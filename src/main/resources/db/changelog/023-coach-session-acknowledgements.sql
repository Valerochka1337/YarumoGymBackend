--liquibase formatted sql
--changeset codex:023-coach-session-acknowledgements
--comment: Upgrade the original 022 and the intermediate development variant without replaying its event backfill.
ALTER TABLE coach_session_events ADD COLUMN IF NOT EXISTS response JSONB;
