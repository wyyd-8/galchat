ALTER TABLE group_chat_reply_step
    ADD COLUMN parent_step_id BIGINT,
    ADD COLUMN root_step_id BIGINT,
    ADD COLUMN interaction_type VARCHAR(64),
    ADD COLUMN interaction_seq INT,
    ADD COLUMN prompt_message_id BIGINT;

CREATE INDEX idx_reply_step_parent
    ON group_chat_reply_step (parent_step_id);

CREATE INDEX idx_reply_step_root_interaction
    ON group_chat_reply_step (root_step_id, interaction_seq);

CREATE INDEX idx_reply_step_prompt_message
    ON group_chat_reply_step (prompt_message_id);
