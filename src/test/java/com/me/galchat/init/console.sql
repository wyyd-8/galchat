-- 1. Ensure pgvector is available.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE user_info (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    birthday DATE,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE world_template (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    image VARCHAR(255),
    author VARCHAR(255),
    background TEXT,
    character_ids BIGINT[] DEFAULT '{}',
    author_id BIGINT,
    visible BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_world_template_author_id
    ON world_template (author_id);

CREATE TABLE world_detail (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL,
    about VARCHAR(255),
    details TEXT
);

CREATE INDEX idx_world_detail_world_id
    ON world_detail (world_id);

CREATE TABLE character_template (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    image VARCHAR(255),
    background TEXT,
    personality TEXT,
    favorability JSONB,
    init_favor INT DEFAULT 0
);

CREATE TABLE user_world_prefix (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    world_id BIGINT NOT NULL,
    name VARCHAR(255),
    image VARCHAR(255),
    acitve_push_status BOOLEAN DEFAULT FALSE,
    favor_system_status VARCHAR(50) DEFAULT 'EASY',
    eot_detection_status BOOLEAN DEFAULT FALSE,
    think_status BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_user_world_prefix_user_id
    ON user_world_prefix (user_id);

CREATE TABLE user_chat_history (
    id BIGSERIAL PRIMARY KEY,
    user_world_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    content TEXT,
    type VARCHAR(50),
    user_message_id BIGINT,
    step_no INT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_chat_history_conversation_id
    ON user_chat_history (user_world_id, character_id, id);

CREATE TABLE user_chat_thinking_history (
    id BIGSERIAL PRIMARY KEY,
    user_message_id BIGINT NOT NULL,
    step_no INT,
    reasoning_content TEXT
);

CREATE INDEX idx_user_chat_thinking_history_user_step
    ON user_chat_thinking_history (user_message_id, step_no, id);

CREATE TABLE user_chat_tool_call (
    id BIGSERIAL PRIMARY KEY,
    user_message_id BIGINT NOT NULL,
    step_no INT,
    tool_call_id VARCHAR(255),
    tool_name VARCHAR(255),
    tool_arguments TEXT,
    tool_result TEXT
);

CREATE INDEX idx_user_chat_tool_call_user_step
    ON user_chat_tool_call (user_message_id, step_no, id);

CREATE INDEX idx_user_chat_tool_call_tool_call_id
    ON user_chat_tool_call (tool_call_id);

CREATE TABLE user_character_info (
    user_world_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    character_name VARCHAR(255),
    character_image VARCHAR(255),
    last_chat_time TIMESTAMP,
    last_chat_content TEXT,
    favor_value INT DEFAULT 0,
    PRIMARY KEY (user_world_id, character_id)
);

CREATE TABLE user_character_favor_log (
    id BIGSERIAL PRIMARY KEY,
    user_world_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    favor_update INT,
    binding_chat BIGINT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_character_favor_log_world_character
    ON user_character_favor_log (user_world_id, character_id);

CREATE TABLE world_story_event (
    id BIGSERIAL PRIMARY KEY,
    user_world_id BIGINT NOT NULL,
    title VARCHAR(255),
    theme VARCHAR(255),
    current_scene TEXT,
    opening TEXT,
    summary TEXT,
    status VARCHAR(50),
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_world_story_event_user_status
    ON world_story_event (user_world_id, status, id);

CREATE TABLE world_story_event_character (
    id BIGSERIAL PRIMARY KEY,
    story_event_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    start_message_id BIGINT,
    end_message_id BIGINT
);

CREATE INDEX idx_world_story_event_character_story
    ON world_story_event_character (story_event_id, id);

CREATE TABLE world_event_log (
    id BIGSERIAL PRIMARY KEY,
    user_world_id BIGINT NOT NULL,
    event_description TEXT,
    visible_characters BIGINT[] DEFAULT '{}',
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    title VARCHAR(255),
    story_event_id BIGINT
);

CREATE INDEX idx_world_event_log_user_world_timestamp
    ON world_event_log (user_world_id, timestamp, id);

CREATE INDEX idx_world_event_log_story_event_id
    ON world_event_log (story_event_id);

CREATE INDEX idx_world_event_log_visible_characters
    ON world_event_log USING GIN (visible_characters);

CREATE TABLE user_event_log (
    id BIGSERIAL PRIMARY KEY,
    user_world_id BIGINT NOT NULL,
    character_id BIGINT,
    time TIMESTAMP,
    event_description TEXT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_event_log_world_time
    ON user_event_log (user_world_id, time, id);

CREATE INDEX idx_user_event_log_time_world_character
    ON user_event_log (time, user_world_id, character_id, id);
