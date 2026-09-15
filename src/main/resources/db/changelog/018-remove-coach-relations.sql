--liquibase formatted sql

--changeset codex:018-remove-coach-relations splitStatements:false
-- Coach-origin proposals must go before their version-bound receipts, idempotency operations and
-- factual explanations. They are the only proposal rows coupled to relation snapshots.
DELETE FROM planner_explanations
WHERE proposal_id IN (SELECT id FROM training_proposals WHERE source = 'COACH');

DELETE FROM training_proposal_operations
WHERE proposal_id IN (SELECT id FROM training_proposals WHERE source = 'COACH');

DELETE FROM training_proposal_receipts
WHERE proposal_id IN (SELECT id FROM training_proposals WHERE source = 'COACH');

DELETE FROM training_proposals WHERE source = 'COACH';

DROP TRIGGER IF EXISTS coach_proposal_origin ON training_proposals;
DROP TRIGGER IF EXISTS coach_version_origin ON training_proposal_versions;
DROP FUNCTION IF EXISTS enforce_coach_proposal_origin();

ALTER TABLE training_proposals DROP CONSTRAINT IF EXISTS training_proposals_source_check;
ALTER TABLE training_proposals DROP CONSTRAINT IF EXISTS training_proposals_check;
ALTER TABLE training_proposals DROP COLUMN IF EXISTS author_id;
ALTER TABLE training_proposals DROP COLUMN IF EXISTS origin_relation_id;
ALTER TABLE training_proposal_versions DROP COLUMN IF EXISTS origin_relation_id;
ALTER TABLE training_proposals
  ADD CONSTRAINT training_proposals_source_ai CHECK (source = 'AI');

DROP TABLE IF EXISTS coach_relation_operations;
DROP TABLE IF EXISTS coach_relation_invitations;
DROP TABLE IF EXISTS coach_relation_directory_heads;
DROP TABLE IF EXISTS coach_relations;
DROP TABLE IF EXISTS training_proposal_authors;
