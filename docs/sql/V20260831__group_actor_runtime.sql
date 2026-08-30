CREATE TABLE group_actor_runtime_config (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    actor_key VARCHAR(100) NOT NULL,
    actor_type VARCHAR(50) NOT NULL,
    actor_id BIGINT,
    control_mode VARCHAR(20) NOT NULL DEFAULT 'MODEL',
    model_api_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_group_actor_runtime_actor
        UNIQUE (conversation_id, actor_key),
    CONSTRAINT ck_group_actor_runtime_actor CHECK (
        (actor_type = 'character' AND actor_id IS NOT NULL)
        OR (actor_type = 'kp' AND actor_id IS NULL)
    ),
    CONSTRAINT ck_group_actor_runtime_control CHECK (
        control_mode IN ('MODEL', 'MANUAL')
        AND NOT (actor_type = 'kp' AND control_mode = 'MANUAL')
    )
);

CREATE INDEX idx_group_actor_runtime_conversation
    ON group_actor_runtime_config (conversation_id, id);

ALTER TABLE group_chat_reply_step
    ADD COLUMN execution_mode VARCHAR(20),
    ADD COLUMN model_api_id BIGINT;

ALTER TABLE group_chat_reply_step
    ADD CONSTRAINT ck_group_chat_reply_step_execution_mode CHECK (
        execution_mode IS NULL OR execution_mode IN ('MODEL', 'MANUAL')
    );
