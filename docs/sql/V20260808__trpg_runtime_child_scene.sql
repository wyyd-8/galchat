CREATE TABLE trpg_runtime_child_scene (
    plan_id BIGINT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    scene_name VARCHAR(200) NOT NULL,
    created_step_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_trpg_runtime_child_scene_conversation
    ON trpg_runtime_child_scene (conversation_id, plan_id);
