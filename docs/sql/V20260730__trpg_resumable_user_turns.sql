ALTER TABLE group_chat_turn
    ADD COLUMN IF NOT EXISTS plan_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_group_chat_turn_plan
    ON group_chat_turn (plan_id, id);

CREATE INDEX IF NOT EXISTS idx_group_chat_turn_active
    ON group_chat_turn (conversation_id, status, id DESC);

CREATE INDEX IF NOT EXISTS idx_group_chat_reply_step_waiting
    ON group_chat_reply_step (turn_id, status, step_no);
