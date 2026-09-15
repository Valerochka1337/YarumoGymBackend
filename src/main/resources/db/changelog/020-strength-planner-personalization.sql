--liquibase formatted sql
--changeset codex:020-records-kind-check
ALTER TABLE records DROP CONSTRAINT records_kind_check;
ALTER TABLE records ADD CONSTRAINT records_kind_check CHECK (kind IN (
  'exercise','gym','routine','workout','measurement','schedule','exercise_hint','profile',
  'calendar_plan','calendar_rule','calendar_exception','strength_planner_profile','workout_effort'
));

CREATE UNIQUE INDEX records_one_strength_planner_profile_per_owner
  ON records(user_id) WHERE kind = 'strength_planner_profile';
ALTER TABLE records ADD CONSTRAINT records_strength_planner_profile_live
  CHECK (kind <> 'strength_planner_profile' OR deleted = FALSE);
