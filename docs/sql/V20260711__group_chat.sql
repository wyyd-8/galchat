-- 群聊与跑团会话数据库迁移。当前项目未启用 Flyway，请在部署前手动执行一次。

CREATE TABLE IF NOT EXISTS group_conversation (
    id BIGSERIAL PRIMARY KEY,
    user_world_id BIGINT NOT NULL,
    world_id BIGINT,
    active_reply_plan_id BIGINT,
    mode VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT,
    status VARCHAR(50) NOT NULL,
    version INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_group_conversation_user_status
    ON group_conversation (user_world_id, status, id);

ALTER TABLE group_conversation
    ADD COLUMN IF NOT EXISTS active_reply_plan_id BIGINT,
    ADD COLUMN IF NOT EXISTS title VARCHAR(255) NOT NULL DEFAULT '群聊',
    ADD COLUMN IF NOT EXISTS summary TEXT,
    ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP,
    DROP COLUMN IF EXISTS opening,
    DROP COLUMN IF EXISTS ended_at,
    DROP COLUMN IF EXISTS story_event_id;

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

CREATE TABLE IF NOT EXISTS group_reply_plan (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    source VARCHAR(20) NOT NULL,
    context_id BIGINT,
    resume_plan_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_group_reply_plan_conversation
    ON group_reply_plan (conversation_id, id);

CREATE TABLE IF NOT EXISTS group_reply_plan_item (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    group_key VARCHAR(100) NOT NULL,
    group_name VARCHAR(200) NOT NULL,
    group_order INT NOT NULL,
    item_order INT NOT NULL,
    actor_type VARCHAR(50) NOT NULL,
    actor_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_group_reply_plan_item_order
    ON group_reply_plan_item (plan_id, group_order, item_order, id);

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
    plan_item_id BIGINT,
    step_no INT NOT NULL,
    action_type VARCHAR(50) NOT NULL,
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

ALTER TABLE group_chat_reply_step
    ADD COLUMN IF NOT EXISTS plan_item_id BIGINT,
    ADD COLUMN IF NOT EXISTS action_type VARCHAR(50) NOT NULL DEFAULT 'chat_reply';

CREATE TABLE IF NOT EXISTS group_chat_tool_call (
    id BIGSERIAL PRIMARY KEY,
    reply_step_id BIGINT NOT NULL,
    tool_step_no INT NOT NULL,
    tool_call_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    tool_arguments TEXT,
    tool_result TEXT,
    dice_roll_summary_id BIGINT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_group_chat_tool_call
    ON group_chat_tool_call (reply_step_id, tool_call_id);
CREATE INDEX IF NOT EXISTS idx_group_chat_tool_call_step
    ON group_chat_tool_call (reply_step_id, tool_step_no, id);

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

CREATE TABLE IF NOT EXISTS group_chat_topic (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    start_sequence BIGINT NOT NULL,
    boundary_reason VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_group_chat_topic_start
    ON group_chat_topic (conversation_id, start_sequence);
CREATE INDEX IF NOT EXISTS idx_group_chat_topic_recent
    ON group_chat_topic (conversation_id, start_sequence DESC, id DESC);

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

DROP TABLE IF EXISTS group_chat_thinking;
DROP TABLE IF EXISTS world_story_event_character;
DROP TABLE IF EXISTS world_story_event;

DROP INDEX IF EXISTS idx_world_event_log_story_event_id;
ALTER TABLE world_event_log
    DROP COLUMN IF EXISTS story_event_id,
    ADD COLUMN IF NOT EXISTS conversation_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_world_event_log_conversation_id
    ON world_event_log (conversation_id);
