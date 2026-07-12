-- 群聊第一版数据库迁移。当前项目未启用 Flyway，请在部署前手动执行一次。

CREATE TABLE IF NOT EXISTS group_conversation (
    id BIGSERIAL PRIMARY KEY,
    user_world_id BIGINT NOT NULL,
    world_id BIGINT,
    story_event_id BIGINT,
    mode VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    version INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_group_conversation_user_status
    ON group_conversation (user_world_id, status, id);

CREATE TABLE IF NOT EXISTS group_chat_member (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    actor_type VARCHAR(50) NOT NULL,
    actor_id BIGINT,
    position INT NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    talkativeness DOUBLE PRECISION DEFAULT 0.5
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_group_chat_member_actor
    ON group_chat_member (conversation_id, actor_type, actor_id);

CREATE TABLE IF NOT EXISTS group_chat_turn (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    trigger_message_id BIGINT,
    client_request_id VARCHAR(100),
    policy VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    revision INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_group_chat_turn_request
    ON group_chat_turn (conversation_id, client_request_id);

CREATE TABLE IF NOT EXISTS group_chat_reply_step (
    id BIGSERIAL PRIMARY KEY,
    turn_id BIGINT NOT NULL,
    step_no INT NOT NULL,
    speaker_type VARCHAR(50) NOT NULL,
    speaker_id BIGINT,
    force_reply BOOLEAN DEFAULT FALSE,
    status VARCHAR(50) NOT NULL,
    output_message_id BIGINT,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_group_chat_reply_step_no
    ON group_chat_reply_step (turn_id, step_no);

CREATE TABLE IF NOT EXISTS group_chat_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    scene_id BIGINT,
    turn_id BIGINT,
    reply_step_id BIGINT,
    speaker_type VARCHAR(50) NOT NULL,
    speaker_id BIGINT,
    message_kind VARCHAR(50) NOT NULL,
    visibility VARCHAR(50) DEFAULT 'public',
    content TEXT,
    sequence_no BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_group_chat_message_sequence
    ON group_chat_message (conversation_id, sequence_no);
CREATE INDEX IF NOT EXISTS idx_group_chat_message_turn
    ON group_chat_message (turn_id, id);

CREATE TABLE IF NOT EXISTS group_chat_thinking (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    reasoning_content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_group_chat_thinking_message
    ON group_chat_thinking (message_id);

CREATE TABLE IF NOT EXISTS group_context_summary (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    scene_id BIGINT,
    start_sequence BIGINT NOT NULL,
    end_sequence BIGINT NOT NULL,
    summary TEXT NOT NULL,
    version INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_group_context_summary_conversation
    ON group_context_summary (conversation_id, end_sequence DESC);

ALTER TABLE world_story_event
    ADD COLUMN IF NOT EXISTS conversation_id BIGINT;
