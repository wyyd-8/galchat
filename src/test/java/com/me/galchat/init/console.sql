-- Canonical test/development database initialization script.
-- Includes the complete schema after all changes made since origin/main.
-- Run against an empty PostgreSQL database; incremental migration fragments are not required.

-- 1. Ensure pgvector is available.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE user_info (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    birthday DATE,
    dice_skin VARCHAR(50) NOT NULL DEFAULT 'default',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE world_template (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    image VARCHAR(255),
    author VARCHAR(255),
    background TEXT,
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
    world_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    image VARCHAR(255),
    background TEXT,
    personality TEXT,
    coc_play_style TEXT,
    favorability JSONB,
    init_favor INT DEFAULT 0
);

CREATE INDEX idx_character_template_world_id
    ON character_template (world_id);

CREATE TABLE user_world_prefix (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    world_id BIGINT NOT NULL,
    name VARCHAR(255),
    image VARCHAR(255),
    acitve_push_status BOOLEAN DEFAULT FALSE,
    daily_companion_mode BOOLEAN DEFAULT FALSE,
    favor_system_status VARCHAR(50) DEFAULT 'EASY',
    eot_detection_status BOOLEAN DEFAULT FALSE,
    think_status BOOLEAN DEFAULT FALSE,
    my_world boolean DEFAULT FALSE,
    add_special_prompt BOOLEAN DEFAULT FALSE
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

CREATE TABLE coc_module (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    author VARCHAR(255),
    era VARCHAR(255),
    introduction TEXT NOT NULL,
    investigator_creation TEXT,
    cover_url TEXT,
    player_count VARCHAR(100),
    estimated_duration VARCHAR(100),
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE coc_module_context (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL,
    truth_background TEXT,
    investigator_intro TEXT,
    timeline TEXT,
    special_rules TEXT,
    keeper_guidance TEXT,
    ending_content TEXT,
    extra_content TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_coc_module_context_module
    ON coc_module_context (module_id);

CREATE TABLE coc_module_location (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_coc_module_location_name
    ON coc_module_location (module_id, name);

CREATE TABLE coc_module_clue (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    important BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_coc_module_clue_title
    ON coc_module_clue (module_id, title);

CREATE TABLE coc_module_material (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    image_url TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_coc_module_material_title
    ON coc_module_material (module_id, title);

CREATE TABLE coc_module_character (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    card_data JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_coc_module_character_module_order
    ON coc_module_character (module_id, sort_order, id);

CREATE TABLE group_conversation (
    id BIGSERIAL PRIMARY KEY,
    user_world_id BIGINT NOT NULL,
    world_id BIGINT,
    module_id BIGINT,
    active_reply_plan_id BIGINT,
    mode VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT,
    status VARCHAR(50) NOT NULL,
    version INT DEFAULT 0,
    game_day_no INT,
    game_time_period VARCHAR(20),
    game_time_revision INT NOT NULL DEFAULT 0,
    game_time_changed_step_id BIGINT,
    game_time_updated_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP,
    CONSTRAINT ck_group_conversation_game_time CHECK (
        (game_day_no IS NULL AND game_time_period IS NULL)
        OR (game_day_no >= 1 AND game_time_period IN (
            'DAWN', 'MORNING', 'NOON', 'AFTERNOON',
            'EVENING', 'LATE_NIGHT'))
    )
);

CREATE INDEX idx_group_conversation_user_status
    ON group_conversation (user_world_id, status, id);
CREATE INDEX idx_group_conversation_module
    ON group_conversation (module_id, id);

CREATE TABLE group_chat_member (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    actor_type VARCHAR(50) NOT NULL,
    actor_id BIGINT,
    position INT NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    talkativeness DOUBLE PRECISION DEFAULT 0.5
);

CREATE UNIQUE INDEX uk_group_chat_member_actor
    ON group_chat_member (conversation_id, actor_type, actor_id);

CREATE TABLE group_reply_plan (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    source VARCHAR(20) NOT NULL,
    context_id BIGINT,
    execution_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    parent_plan_id BIGINT,
    resume_plan_id BIGINT,
    next_plan_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_group_reply_plan_conversation
    ON group_reply_plan (conversation_id, id);

CREATE INDEX idx_group_reply_plan_parent
    ON group_reply_plan (parent_plan_id, id);

CREATE TABLE trpg_runtime_child_scene (
    plan_id BIGINT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    scene_name VARCHAR(200) NOT NULL,
    created_step_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_trpg_runtime_child_scene_conversation
    ON trpg_runtime_child_scene (conversation_id, plan_id);

CREATE TABLE group_reply_plan_item (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    item_order INT NOT NULL,
    actor_type VARCHAR(50) NOT NULL,
    actor_id BIGINT,
    subject_character_id BIGINT,
    subject_character_name VARCHAR(255),
    participant_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_group_reply_plan_item_order
    ON group_reply_plan_item (plan_id, item_order, id);

CREATE TABLE group_chat_turn (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    trigger_message_id BIGINT,
    client_request_id VARCHAR(100),
    plan_id BIGINT,
    plan_source VARCHAR(50) NOT NULL,
    plan_context_id BIGINT,
    status VARCHAR(50) NOT NULL,
    revision INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_group_chat_turn_request
    ON group_chat_turn (conversation_id, client_request_id);

CREATE INDEX idx_group_chat_turn_plan
    ON group_chat_turn (plan_id, id);

CREATE INDEX idx_group_chat_turn_active
    ON group_chat_turn (conversation_id, status, id DESC);

CREATE TABLE group_chat_reply_step (
    id BIGSERIAL PRIMARY KEY,
    turn_id BIGINT NOT NULL,
    group_key VARCHAR(100) NOT NULL,
    group_name VARCHAR(200) NOT NULL,
    group_order INT NOT NULL,
    item_order INT NOT NULL,
    step_no INT NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    speaker_type VARCHAR(50) NOT NULL,
    speaker_id BIGINT,
    subject_character_id BIGINT,
    force_reply BOOLEAN DEFAULT FALSE,
    status VARCHAR(50) NOT NULL,
    output_message_id BIGINT,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_group_chat_reply_step_no
    ON group_chat_reply_step (turn_id, step_no);

CREATE INDEX idx_group_chat_reply_step_waiting
    ON group_chat_reply_step (turn_id, status, step_no);

CREATE TABLE group_chat_agent_decision (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    reply_step_id BIGINT NOT NULL
        REFERENCES group_chat_reply_step (id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_group_chat_agent_decision_step
    ON group_chat_agent_decision (reply_step_id);

CREATE TABLE group_chat_tool_call (
    id BIGSERIAL PRIMARY KEY,
    reply_step_id BIGINT NOT NULL,
    tool_step_no INT NOT NULL,
    tool_call_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    tool_arguments TEXT,
    tool_result TEXT,
    dice_roll_summary_id BIGINT
);

CREATE UNIQUE INDEX uk_group_chat_tool_call
    ON group_chat_tool_call (reply_step_id, tool_call_id);

CREATE INDEX idx_group_chat_tool_call_step
    ON group_chat_tool_call (reply_step_id, tool_step_no, id);

CREATE TABLE group_chat_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    scene_id BIGINT,
    scene_plan_id BIGINT,
    turn_id BIGINT,
    reply_step_id BIGINT,
    client_request_id VARCHAR(100),
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

CREATE UNIQUE INDEX uk_group_chat_message_sequence
    ON group_chat_message (conversation_id, sequence_no);

CREATE INDEX idx_group_chat_message_turn
    ON group_chat_message (turn_id, id);

CREATE UNIQUE INDEX uk_group_chat_message_request
    ON group_chat_message (conversation_id, client_request_id);

CREATE TABLE group_chat_topic (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    start_sequence BIGINT NOT NULL,
    boundary_reason VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_group_chat_topic_start
    ON group_chat_topic (conversation_id, start_sequence);

CREATE INDEX idx_group_chat_topic_recent
    ON group_chat_topic (conversation_id, start_sequence DESC, id DESC);

CREATE TABLE group_context_summary (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    scene_id BIGINT,
    scene_plan_id BIGINT,
    start_sequence BIGINT NOT NULL,
    end_sequence BIGINT NOT NULL,
    summary TEXT NOT NULL,
    version INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_group_context_summary_conversation
    ON group_context_summary (conversation_id, end_sequence DESC);
CREATE INDEX idx_group_context_summary_interval
    ON group_context_summary (
        conversation_id, start_sequence, end_sequence);

CREATE INDEX idx_group_context_summary_scene_plan
    ON group_context_summary (scene_plan_id, version DESC, id DESC);

CREATE TABLE dice_roll_summary (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    reason TEXT NOT NULL,
    total_result TEXT,
    round_count INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (round_count >= 1),
    CHECK (status IN ('PENDING', 'COMPLETED'))
);

CREATE INDEX idx_dice_roll_summary_conversation
    ON dice_roll_summary (conversation_id, id);

CREATE TABLE dice_roll_result (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    summary_id BIGINT NOT NULL,
    character_id BIGINT,
    round_no INT NOT NULL,
    display_order INT NOT NULL,
    display_type VARCHAR(32),
    reason TEXT NOT NULL,
    result_data JSONB NOT NULL,
    resolution_data JSONB NOT NULL
        DEFAULT '{"version":1,"type":"LEGACY","rule":{},"outcome":null,"effect":null}'::jsonb,
    resolved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (round_no >= 1),
    CHECK (display_order >= 1)
);

CREATE UNIQUE INDEX uk_dice_roll_result_position
    ON dice_roll_result (summary_id, round_no, display_order);

CREATE INDEX idx_dice_roll_result_summary
    ON dice_roll_result (summary_id, round_no, display_order, id);

CREATE TABLE user_character_info (
    user_world_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    character_name VARCHAR(255),
    character_image VARCHAR(255),
    last_chat_time TIMESTAMP,
    last_chat_content TEXT,
    favor_value INT DEFAULT 0,
    user_info_prompt TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (user_world_id, character_id)
);

CREATE TABLE user_character_favor_log (
    id BIGSERIAL PRIMARY KEY,
    user_world_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    favor_update INT,
    binding_type VARCHAR(32) NOT NULL,
    binding_chat BIGINT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_character_favor_log_binding_type
        CHECK (binding_type IN ('SINGLE_MESSAGE', 'GROUP_REPLY_STEP'))
);

CREATE INDEX idx_user_character_favor_log_binding
    ON user_character_favor_log
       (user_world_id, character_id, binding_type, binding_chat);

CREATE TABLE world_event_log (
    id BIGSERIAL PRIMARY KEY,
    user_world_id BIGINT NOT NULL,
    event_description TEXT,
    visible_characters BIGINT[] DEFAULT '{}',
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    title VARCHAR(255),
    conversation_id BIGINT
);

CREATE INDEX idx_world_event_log_user_world_timestamp
    ON world_event_log (user_world_id, timestamp, id);

CREATE INDEX idx_world_event_log_conversation_id
    ON world_event_log (conversation_id);

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

CREATE TABLE user_world_save (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    user_world_id BIGINT NOT NULL,
    remark TEXT,
    saved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    format_version INT NOT NULL DEFAULT 1,
    character_favors JSONB NOT NULL DEFAULT '[]',
    snapshot JSONB NOT NULL,
    UNIQUE (user_id, user_world_id)
);

CREATE INDEX idx_user_world_save_world
    ON user_world_save (user_world_id);

CREATE TABLE coc_character (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    participant_id BIGINT,
    name VARCHAR(255) NOT NULL,
    player_name VARCHAR(255),
    image VARCHAR(1024),
    occupation VARCHAR(255),
    sex VARCHAR(50),
    age INT,
    era VARCHAR(100),
    birthplace VARCHAR(255),
    residence VARCHAR(255),
    creation_method VARCHAR(30),
    str SMALLINT NOT NULL,
    con SMALLINT NOT NULL,
    siz SMALLINT NOT NULL,
    dex SMALLINT NOT NULL,
    app SMALLINT NOT NULL,
    int_value SMALLINT NOT NULL,
    pow SMALLINT NOT NULL,
    edu SMALLINT NOT NULL,
    damage_bonus VARCHAR(20) NOT NULL,
    build SMALLINT NOT NULL,
    mov SMALLINT NOT NULL,
    hp_current SMALLINT NOT NULL,
    hp_max SMALLINT NOT NULL,
    san_current SMALLINT NOT NULL,
    san_max SMALLINT NOT NULL,
    mp_current SMALLINT NOT NULL,
    mp_max SMALLINT NOT NULL,
    luck_current SMALLINT,
    armor SMALLINT DEFAULT 0,
    major_wound BOOLEAN DEFAULT FALSE,
    unconscious BOOLEAN DEFAULT FALSE,
    dying BOOLEAN DEFAULT FALSE,
    dead BOOLEAN DEFAULT FALSE,
    temporary_insanity BOOLEAN DEFAULT FALSE,
    temporary_insanity_phase VARCHAR(20),
    temporary_insanity_remaining_rounds INT,
    quick_notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_coc_character_run_actor
    ON coc_character (run_id, actor_type, id);

CREATE INDEX idx_coc_character_participant
    ON coc_character (participant_id);

CREATE UNIQUE INDEX uk_coc_character_run_participant
    ON coc_character (run_id, participant_id)
    WHERE participant_id IS NOT NULL;

CREATE UNIQUE INDEX uk_coc_character_run_player
    ON coc_character (run_id)
    WHERE participant_id IS NULL AND actor_type = 'PLAYER';

CREATE TABLE coc_skill_def (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    category VARCHAR(100),
    base_value SMALLINT,
    base_formula VARCHAR(255),
    allow_specialization BOOLEAN DEFAULT FALSE,
    parent_name VARCHAR(255),
    is_core BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_coc_skill_def_parent
    ON coc_skill_def (parent_name);

INSERT INTO coc_skill_def (
    name,
    category,
    base_value,
    base_formula,
    allow_specialization,
    parent_name,
    is_core
) VALUES
    ('会计', '知识', 5, NULL, FALSE, NULL, TRUE),
    ('人类学', '知识', 1, NULL, FALSE, NULL, TRUE),
    ('估价', '知识', 5, NULL, FALSE, NULL, TRUE),
    ('考古学', '知识', 1, NULL, FALSE, NULL, TRUE),
    ('取悦', '社交', 15, NULL, FALSE, NULL, TRUE),
    ('攀爬', '行动', 20, NULL, FALSE, NULL, TRUE),
    ('信用评级', '资源', 0, NULL, FALSE, NULL, TRUE),
    ('克苏鲁神话', '知识', 0, NULL, FALSE, NULL, TRUE),
    ('乔装', '社交', 5, NULL, FALSE, NULL, TRUE),
    ('闪避', '战斗', NULL, 'DEX/2', FALSE, NULL, TRUE),
    ('汽车驾驶', '行动', 20, NULL, FALSE, NULL, TRUE),
    ('电气维修', '技术', 10, NULL, FALSE, NULL, TRUE),
    ('话术', '社交', 5, NULL, FALSE, NULL, TRUE),
    ('急救', '医疗', 30, NULL, FALSE, NULL, TRUE),
    ('历史', '知识', 5, NULL, FALSE, NULL, TRUE),
    ('恐吓', '社交', 15, NULL, FALSE, NULL, TRUE),
    ('跳跃', '行动', 20, NULL, FALSE, NULL, TRUE),
    ('法律', '知识', 5, NULL, FALSE, NULL, TRUE),
    ('图书馆使用', '知识', 20, NULL, FALSE, NULL, TRUE),
    ('聆听', '感知', 20, NULL, FALSE, NULL, TRUE),
    ('锁匠', '技术', 1, NULL, FALSE, NULL, TRUE),
    ('机械维修', '技术', 10, NULL, FALSE, NULL, TRUE),
    ('医学', '医疗', 1, NULL, FALSE, NULL, TRUE),
    ('博物学', '知识', 10, NULL, FALSE, NULL, TRUE),
    ('导航', '行动', 10, NULL, FALSE, NULL, TRUE),
    ('神秘学', '知识', 5, NULL, FALSE, NULL, TRUE),
    ('操作重型机械', '技术', 1, NULL, FALSE, NULL, TRUE),
    ('说服', '社交', 10, NULL, FALSE, NULL, TRUE),
    ('精神分析', '医疗', 1, NULL, FALSE, NULL, TRUE),
    ('心理学', '社交', 10, NULL, FALSE, NULL, TRUE),
    ('骑术', '行动', 5, NULL, FALSE, NULL, TRUE),
    ('妙手', '行动', 10, NULL, FALSE, NULL, TRUE),
    ('侦查', '感知', 25, NULL, FALSE, NULL, TRUE),
    ('潜行', '行动', 20, NULL, FALSE, NULL, TRUE),
    ('游泳', '行动', 20, NULL, FALSE, NULL, TRUE),
    ('投掷', '行动', 20, NULL, FALSE, NULL, TRUE),
    ('追踪', '感知', 10, NULL, FALSE, NULL, TRUE),

    ('艺术和手艺', '艺术和手艺', 5, NULL, TRUE, NULL, TRUE),
    ('艺术和手艺:表演', '艺术和手艺', 5, NULL, FALSE, '艺术和手艺', TRUE),
    ('艺术和手艺:美术', '艺术和手艺', 5, NULL, FALSE, '艺术和手艺', TRUE),
    ('艺术和手艺:伪造文书', '艺术和手艺', 5, NULL, FALSE, '艺术和手艺', TRUE),
    ('艺术和手艺:摄影', '艺术和手艺', 5, NULL, FALSE, '艺术和手艺', TRUE),

    ('格斗', '格斗', NULL, NULL, TRUE, NULL, TRUE),
    ('格斗:斧', '格斗', 15, NULL, FALSE, '格斗', TRUE),
    ('斗殴', '格斗', 25, NULL, FALSE, '格斗', TRUE),
    ('格斗:链锯', '格斗', 10, NULL, FALSE, '格斗', TRUE),
    ('格斗:连枷', '格斗', 10, NULL, FALSE, '格斗', TRUE),
    ('格斗:绞索', '格斗', 15, NULL, FALSE, '格斗', TRUE),
    ('格斗:矛', '格斗', 20, NULL, FALSE, '格斗', TRUE),
    ('格斗:刀剑', '格斗', 20, NULL, FALSE, '格斗', TRUE),
    ('格斗:鞭', '格斗', 5, NULL, FALSE, '格斗', TRUE),

    ('射击', '射击', NULL, NULL, TRUE, NULL, TRUE),
    ('射击:弓', '射击', 15, NULL, FALSE, '射击', TRUE),
    ('射击:火焰喷射器', '射击', 10, NULL, FALSE, '射击', TRUE),
    ('射击:手枪', '射击', 20, NULL, FALSE, '射击', TRUE),
    ('射击:重武器', '射击', 10, NULL, FALSE, '射击', TRUE),
    ('射击:机枪', '射击', 10, NULL, FALSE, '射击', TRUE),
    ('射击:步枪/霰弹枪', '射击', 25, NULL, FALSE, '射击', TRUE),
    ('射击:冲锋枪', '射击', 15, NULL, FALSE, '射击', TRUE),

    ('语言', '语言', 1, NULL, TRUE, NULL, TRUE),
    ('母语', '语言', NULL, 'EDU', FALSE, '语言', TRUE),

    ('科学', '科学', 1, NULL, TRUE, NULL, TRUE),
    ('科学:天文学', '科学', 1, NULL, FALSE, '科学', TRUE),
    ('科学:生物学', '科学', 1, NULL, FALSE, '科学', TRUE),
    ('科学:植物学', '科学', 1, NULL, FALSE, '科学', TRUE),
    ('科学:化学', '科学', 1, NULL, FALSE, '科学', TRUE),
    ('科学:密码学', '科学', 1, NULL, FALSE, '科学', TRUE),
    ('科学:工程学', '科学', 1, NULL, FALSE, '科学', TRUE),
    ('科学:司法科学', '科学', 1, NULL, FALSE, '科学', TRUE),
    ('科学:地质学', '科学', 1, NULL, FALSE, '科学', TRUE),
    ('科学:数学', '科学', 10, NULL, FALSE, '科学', TRUE),
    ('科学:气象学', '科学', 1, NULL, FALSE, '科学', TRUE),
    ('科学:药学', '科学', 1, NULL, FALSE, '科学', TRUE),
    ('科学:物理学', '科学', 1, NULL, FALSE, '科学', TRUE),
    ('科学:动物学', '科学', 1, NULL, FALSE, '科学', TRUE),

    ('生存', '生存', 10, NULL, TRUE, NULL, TRUE),
    ('操纵', '操纵', 1, NULL, TRUE, NULL, TRUE),

    ('计算机使用', '现代技术', 5, NULL, FALSE, NULL, TRUE),
    ('电子学', '现代技术', 1, NULL, FALSE, NULL, TRUE),

    ('动物驯养', '非常规', 5, NULL, FALSE, NULL, FALSE),
    ('爆破', '非常规', 1, NULL, FALSE, NULL, FALSE),
    ('潜水', '非常规', 1, NULL, FALSE, NULL, FALSE),
    ('催眠', '非常规', 1, NULL, FALSE, NULL, FALSE),
    ('读唇', '非常规', 1, NULL, FALSE, NULL, FALSE),
    ('学识', '非常规', 1, NULL, TRUE, NULL, FALSE),
    ('炮术', '非常规', 1, NULL, TRUE, NULL, FALSE)
ON CONFLICT (name) DO NOTHING;

CREATE TABLE coc_character_skill (
    id BIGSERIAL PRIMARY KEY,
    character_id BIGINT NOT NULL,
    skill_def_id BIGINT,
    display_name VARCHAR(255) NOT NULL,
    category VARCHAR(100),
    specialization VARCHAR(255) NOT NULL DEFAULT '',
    base_value SMALLINT,
    value SMALLINT NOT NULL,
    is_custom BOOLEAN DEFAULT FALSE,
    UNIQUE (character_id, display_name, specialization)
);

CREATE INDEX idx_coc_character_skill_character
    ON coc_character_skill (character_id);

CREATE INDEX idx_coc_character_skill_name
    ON coc_character_skill (character_id, display_name);

CREATE TABLE coc_character_weapon (
    id BIGSERIAL PRIMARY KEY,
    character_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    skill_name VARCHAR(255),
    damage VARCHAR(100),
    range VARCHAR(100),
    attacks_per_round VARCHAR(50),
    ammo_capacity INT,
    remaining_ammo INT,
    malfunction VARCHAR(50),
    is_broken BOOLEAN DEFAULT FALSE,
    abnormal BOOLEAN NOT NULL DEFAULT FALSE,
    risk_tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    notes TEXT
);

CREATE INDEX idx_coc_character_weapon_character
    ON coc_character_weapon (character_id);

CREATE TABLE coc_character_profile (
    id BIGSERIAL PRIMARY KEY,
    character_id BIGINT NOT NULL UNIQUE,
    appearance TEXT,
    ideology TEXT,
    significant_people TEXT,
    meaningful_locations TEXT,
    treasured_possessions TEXT,
    traits TEXT,
    key_connection_category VARCHAR(50),
    key_connection_text TEXT,
    injuries_and_scars TEXT,
    phobias_and_manias TEXT,
    equipment_text TEXT,
    assets_text TEXT,
    spending_level VARCHAR(100),
    cash VARCHAR(100),
    notes TEXT
);

CREATE TABLE coc_character_creation_draft (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    participant_id BIGINT,
    creation_mode VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    current_step VARCHAR(50) NOT NULL,
    next_action VARCHAR(50),
    operation_status VARCHAR(30) NOT NULL DEFAULT 'IDLE',
    version INT NOT NULL DEFAULT 1,
    rules_version INT NOT NULL DEFAULT 1,
    state JSONB NOT NULL,
    last_request_id VARCHAR(100),
    last_action VARCHAR(50),
    last_error_code VARCHAR(100),
    result_character_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_coc_character_creation_draft_owner
    ON coc_character_creation_draft (owner_user_id, run_id, participant_id, id);

CREATE UNIQUE INDEX uk_coc_character_creation_draft_active
    ON coc_character_creation_draft (owner_user_id, run_id, COALESCE(participant_id, -1))
    WHERE status IN ('IN_PROGRESS', 'PREVIEW_READY');

ALTER TABLE coc_character
    ADD CONSTRAINT ck_coc_character_name_trimmed
        CHECK (name = btrim(name));

CREATE UNIQUE INDEX uk_coc_character_run_name
    ON coc_character (run_id, btrim(name));

CREATE TABLE trpg_combat (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    source_scene_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    order_mode VARCHAR(30) NOT NULL,
    current_round INT NOT NULL DEFAULT 1,
    participants JSONB NOT NULL,
    active_turn_results JSONB NOT NULL DEFAULT '[]'::jsonb,
    start_requested_step_id BIGINT,
    finish_requested_step_id BIGINT,
    start_sequence BIGINT,
    end_sequence BIGINT,
    summary TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP
);

CREATE INDEX idx_trpg_combat_conversation
    ON trpg_combat (conversation_id, id);

CREATE INDEX idx_trpg_combat_status
    ON trpg_combat (status, id);

CREATE TABLE group_turn_checkpoint (
    conversation_id BIGINT PRIMARY KEY,
    turn_id BIGINT NOT NULL,
    reply_step_id BIGINT NOT NULL,
    checkpoint_type VARCHAR(30) NOT NULL,
    message_id BIGINT,
    tool_call_id BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE trpg_save (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    remark VARCHAR(200),
    saved_at TIMESTAMP NOT NULL,
    format_version INT NOT NULL,
    snapshot JSONB NOT NULL
);

CREATE UNIQUE INDEX uk_trpg_save_conversation
    ON trpg_save (conversation_id);

CREATE INDEX idx_trpg_save_user
    ON trpg_save (user_id, saved_at DESC);

CREATE TABLE trpg_auto_save (
    conversation_id BIGINT PRIMARY KEY,
    saved_at TIMESTAMP NOT NULL,
    format_version INT NOT NULL,
    snapshot JSONB NOT NULL
);
