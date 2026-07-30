CREATE TABLE IF NOT EXISTS group_chat_agent_decision (
    id BIGSERIAL PRIMARY KEY,
    reply_step_id BIGINT NOT NULL
        REFERENCES group_chat_reply_step (id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_group_chat_agent_decision_step
    ON group_chat_agent_decision (reply_step_id);
