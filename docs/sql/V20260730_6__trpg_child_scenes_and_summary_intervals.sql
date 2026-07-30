ALTER TABLE group_reply_plan
    ADD COLUMN IF NOT EXISTS parent_plan_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_group_reply_plan_parent
    ON group_reply_plan (parent_plan_id, id);

ALTER TABLE group_reply_plan_item
    ADD COLUMN IF NOT EXISTS participant_status VARCHAR(20)
        NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE group_context_summary
    ADD COLUMN IF NOT EXISTS scene_plan_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_group_context_summary_interval
    ON group_context_summary (
        conversation_id, start_sequence, end_sequence);

CREATE INDEX IF NOT EXISTS idx_group_context_summary_scene_plan
    ON group_context_summary (scene_plan_id, version DESC, id DESC);
