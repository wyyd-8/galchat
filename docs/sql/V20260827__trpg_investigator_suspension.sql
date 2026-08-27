CREATE TABLE IF NOT EXISTS trpg_investigator_suspension (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    subject_character_id BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    suspension_context TEXT NOT NULL,
    origin_context_id BIGINT NOT NULL,
    reentry_context TEXT NULL,
    recovery_scene_name VARCHAR(200) NULL,
    recovery_plan_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_trpg_investigator_suspension_character
        UNIQUE (conversation_id, subject_character_id)
);

CREATE INDEX IF NOT EXISTS idx_trpg_investigator_suspension_state
    ON trpg_investigator_suspension (conversation_id, state);
