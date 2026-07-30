ALTER TABLE coc_character
    ADD CONSTRAINT ck_coc_character_name_trimmed
        CHECK (name = btrim(name));

CREATE UNIQUE INDEX IF NOT EXISTS uk_coc_character_run_name
    ON coc_character (run_id, btrim(name));

ALTER TABLE group_reply_plan_item
    ADD COLUMN IF NOT EXISTS subject_character_id BIGINT;

ALTER TABLE group_chat_reply_step
    ADD COLUMN IF NOT EXISTS subject_character_id BIGINT;

CREATE TABLE IF NOT EXISTS trpg_combat (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    source_scene_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    order_mode VARCHAR(30) NOT NULL,
    current_round INT NOT NULL DEFAULT 1,
    participants JSONB NOT NULL,
    active_turn_results JSONB NOT NULL DEFAULT '[]'::jsonb,
    start_requested_step_id BIGINT,
    finish_requested_step_id BIGINT,
    start_sequence BIGINT,
    end_sequence BIGINT,
    summary TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_trpg_combat_conversation
    ON trpg_combat (conversation_id, id);

CREATE INDEX IF NOT EXISTS idx_trpg_combat_status
    ON trpg_combat (status, id);
