-- COC 静态模组、场景链与 KP 私有快速笔记。
-- 当前项目未启用 Flyway，请在部署前手动执行一次。
-- 按产品约束不建立外键；模组引用保护由 Redis 读写锁和服务层依赖检查完成。

CREATE TABLE IF NOT EXISTS coc_module (
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

CREATE TABLE IF NOT EXISTS coc_module_context (
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
CREATE UNIQUE INDEX IF NOT EXISTS uk_coc_module_context_module
    ON coc_module_context (module_id);

CREATE TABLE IF NOT EXISTS coc_module_location (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL,
    parent_location_id BIGINT,
    name VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_coc_module_location_name
    ON coc_module_location (module_id, name);

CREATE TABLE IF NOT EXISTS coc_module_clue (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    important BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_coc_module_clue_title
    ON coc_module_clue (module_id, title);

CREATE TABLE IF NOT EXISTS coc_module_material (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    image_url TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_coc_module_material_title
    ON coc_module_material (module_id, title);

ALTER TABLE group_conversation
    ADD COLUMN IF NOT EXISTS module_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_group_conversation_module
    ON group_conversation (module_id, id);

ALTER TABLE group_reply_plan
    ADD COLUMN IF NOT EXISTS next_plan_id BIGINT;

ALTER TABLE coc_character
    ADD COLUMN IF NOT EXISTS quick_notes TEXT;
