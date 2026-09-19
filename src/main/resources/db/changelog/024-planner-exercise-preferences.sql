--liquibase formatted sql
--changeset codex:024-records-kind-check
ALTER TABLE records DROP CONSTRAINT records_kind_check;
ALTER TABLE records ADD CONSTRAINT records_kind_check CHECK (kind IN (
  'exercise','gym','routine','workout','measurement','schedule','exercise_hint','profile',
  'calendar_plan','calendar_rule','calendar_exception','strength_planner_profile','workout_effort',
  'planner_exercise_preferences'
));

CREATE UNIQUE INDEX records_one_planner_exercise_preferences_per_owner
  ON records(user_id) WHERE kind = 'planner_exercise_preferences' AND deleted = FALSE;
