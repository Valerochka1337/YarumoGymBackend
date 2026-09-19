--liquibase formatted sql
--changeset yarumo:029-planner-configuration
CREATE TABLE planner_configuration (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    payload TEXT NOT NULL
);
ALTER TABLE calendar_ai_attempts ADD COLUMN plan_config_json TEXT;
ALTER TABLE calendar_planner_refinements ADD COLUMN plan_config_json TEXT;
