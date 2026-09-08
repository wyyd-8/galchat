-- Apply when the earlier completion-table version has already been installed.
-- Preserve complete reports; discard intermediate generation checkpoints.
BEGIN;
UPDATE trpg_completion SET data = NULL
WHERE data IS NOT NULL AND (
    data->'epilogues' IS NULL OR data->'epilogues' = 'null'::jsonb
    OR data->'overview' IS NULL OR data->'overview' = 'null'::jsonb
);
ALTER TABLE trpg_completion DROP COLUMN IF EXISTS status;
ALTER TABLE trpg_completion DROP COLUMN IF EXISTS completed_at;
ALTER TABLE trpg_completion DROP COLUMN IF EXISTS archived_at;
COMMIT;
