--liquibase formatted sql

--changeset codex:017-planner-explanations
CREATE TABLE planner_explanations (
    proposal_id UUID NOT NULL,
    version INT NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY(proposal_id, version),
    FOREIGN KEY(proposal_id, version)
      REFERENCES training_proposal_versions(proposal_id, version) ON DELETE CASCADE
);
