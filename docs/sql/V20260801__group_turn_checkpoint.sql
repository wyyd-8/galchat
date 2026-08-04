CREATE TABLE IF NOT EXISTS group_turn_checkpoint (
    conversation_id BIGINT PRIMARY KEY,
    turn_id BIGINT NOT NULL,
    reply_step_id BIGINT NOT NULL,
    checkpoint_type VARCHAR(30) NOT NULL,
    message_id BIGINT,
    tool_call_id BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
