-- One completion per run. Generation/retry status belongs to the action turn;
-- archive time is group_conversation.closed_at. No historical report backfill.
CREATE TABLE IF NOT EXISTS trpg_completion (
    conversation_id BIGINT PRIMARY KEY,
    turn_id BIGINT NOT NULL,
    data JSONB
);
