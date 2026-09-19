--liquibase formatted sql
--changeset codex:025-calendar-planner-refinements
CREATE TABLE calendar_planner_refinements (
  owner_id UUID NOT NULL,
  request_id UUID NOT NULL,
  proposal_id UUID NOT NULL,
  expected_version INTEGER NOT NULL,
  request_sha256 CHAR(64) NOT NULL,
  raw_request BYTEA NOT NULL,
  receipt JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (owner_id, request_id)
);
