--liquibase formatted sql
--changeset codex:024-calendar-ai-v2-receipts
ALTER TABLE calendar_ai_attempts ADD COLUMN v2_receipt JSONB;
ALTER TABLE calendar_ai_attempts ADD CONSTRAINT calendar_ai_attempts_v2_receipt_state
  CHECK (v2_receipt IS NULL OR state='SUCCEEDED');
