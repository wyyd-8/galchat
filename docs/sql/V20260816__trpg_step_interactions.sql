ALTER TABLE group_chat_reply_step
    ADD COLUMN parent_step_id BIGINT NULL AFTER step_no,
    ADD COLUMN root_step_id BIGINT NULL AFTER parent_step_id,
    ADD COLUMN interaction_type VARCHAR(64) NULL AFTER root_step_id,
    ADD COLUMN interaction_seq INT NULL AFTER interaction_type,
    ADD COLUMN prompt_message_id BIGINT NULL AFTER interaction_seq,
    ADD INDEX idx_reply_step_parent (parent_step_id),
    ADD INDEX idx_reply_step_root_interaction (root_step_id, interaction_seq),
    ADD INDEX idx_reply_step_prompt_message (prompt_message_id);
