--liquibase formatted sql
--changeset codex:032-planner-exercise-accents
ALTER TABLE records DROP CONSTRAINT records_kind_check;
ALTER TABLE records ADD CONSTRAINT records_kind_check CHECK (kind IN (
  'exercise','gym','routine','workout','measurement','schedule','exercise_hint','profile',
  'calendar_plan','calendar_rule','calendar_exception','strength_planner_profile','workout_effort',
  'planner_exercise_preferences','planner_exercise_accents'
));
CREATE UNIQUE INDEX records_one_planner_exercise_accents_per_owner
  ON records(user_id) WHERE kind = 'planner_exercise_accents' AND deleted = FALSE;
