--liquibase formatted sql
--changeset codex:024-calendar-refinement-leases
ALTER TABLE calendar_planner_refinements ALTER COLUMN receipt DROP NOT NULL;
ALTER TABLE calendar_planner_refinements ADD COLUMN lease_until TIMESTAMPTZ;
UPDATE calendar_planner_refinements SET lease_until=created_at WHERE lease_until IS NULL;
ALTER TABLE calendar_planner_refinements ALTER COLUMN lease_until SET NOT NULL;
