--liquibase formatted sql

--changeset codex:033-concurrent-calendar-draft-jobs
-- Independent requests remain current until explicitly replaced or cancelled.
-- The primary key serves owner/request lookups; the existing queue index remains unchanged.
DROP INDEX calendar_draft_jobs_current;
