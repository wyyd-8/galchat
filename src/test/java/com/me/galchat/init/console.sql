-- 1. 确保安装并启用了 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 创建 user 表 ("user" 为保留字，需加双引号)
CREATE TABLE "user" (
                        id BIGSERIAL PRIMARY KEY,
                        username VARCHAR(255) NOT NULL,
                        email VARCHAR(255) UNIQUE NOT NULL,
                        password VARCHAR(255) NOT NULL,
                        eot_detection_status BOOLEAN DEFAULT FALSE,
                        birthday DATE,
                        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. 创建 world_template 表
CREATE TABLE world_template (
                                id BIGSERIAL PRIMARY KEY,
                                background TEXT,
                                character_ids BIGINT[] DEFAULT '{}',
    -- 角色之间的初始化好感度值，适合用 JSONB 存储类似 {"charA_charB": 50, ...} 的映射关系
                                character_init_favor JSONB
);

-- 4. 创建 world_detail 表
CREATE TABLE world_detail (
                              id BIGSERIAL PRIMARY KEY,
                              world_id BIGINT NOT NULL REFERENCES world_template(id) ON DELETE CASCADE,
                              about VARCHAR(255),
                              details TEXT,
                              details_vector vector(1024) -- 向量字段，请根据你的模型实际维度(如 768, 1536)调整
);

-- 5. 创建 character_template 表
CREATE TABLE character_template (
                                    id BIGSERIAL PRIMARY KEY,
                                    name VARCHAR(255) NOT NULL,
                                    background TEXT,
                                    personality TEXT,
    -- 分层好感度对应的prompt，使用 JSONB 存储，例如 {"10": "冷漠", "50": "友好"}
                                    favorability JSONB,
                                    init_favor INT DEFAULT 0
);

-- 6. 创建 user_world_prefix 表
CREATE TABLE user_world_prefix (
                                   id BIGSERIAL PRIMARY KEY,
                                   user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
                                   world_id BIGINT NOT NULL REFERENCES world_template(id) ON DELETE CASCADE,
                                   acitve_push_status BOOLEAN DEFAULT FALSE,
                                   push_time TIMESTAMP WITH TIME ZONE,
                                   random_event_status BOOLEAN DEFAULT FALSE,
                                   connect_other_character_status BOOLEAN DEFAULT FALSE,
    -- 注释中说明可以做分级设计（EASY, MEDIUM, HARD），所以这里不使用 boolean，而是 VARCHAR 或 ENUM
                                   favor_system_status VARCHAR(50) DEFAULT 'EASY'
);

-- 7. 创建 user_chat_history 表
CREATE TABLE user_chat_history (
    -- 尽管你的设计里没写自增 ID，但聊天记录表建议加上一个 id 作为主键方便后续单条溯源
                                   id BIGSERIAL PRIMARY KEY,
                                   user_world_id BIGINT NOT NULL REFERENCES user_world_prefix(id) ON DELETE CASCADE,
                                   character_id BIGINT NOT NULL,
                                   content TEXT,
                                   type VARCHAR(50),
                                   timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 为 user_chat_history 的前两个字段创建联合索引，方便快速查询特定用户在特定世界中与某角色的记录
CREATE INDEX idx_user_chat_history_world_char
    ON user_chat_history (user_world_id, character_id);

-- 8. 创建 user_character_favor 表
CREATE TABLE user_character_favor (
                                      user_world_id BIGINT NOT NULL REFERENCES user_world_prefix(id) ON DELETE CASCADE,
                                      character_id BIGINT NOT NULL,
                                      favor_value INT DEFAULT 0,
    -- 这张表本身就是关联关系，直接把这两个字段设为复合主键，既保证了唯一性也自带了高效的联合索引
                                      PRIMARY KEY (user_world_id, character_id)
);

-- 9. 创建 user_character_favor_log 表
CREATE TABLE user_character_favor_log (
                                          id BIGSERIAL PRIMARY KEY,
                                          user_world_id BIGINT NOT NULL REFERENCES user_world_prefix(id) ON DELETE CASCADE,
                                          character_id BIGINT NOT NULL,
                                          favor_update INT, -- 增加或减少的值，比如 +5 或 -2
                                          binding_chat BIGINT REFERENCES user_chat_history(id) ON DELETE SET NULL, -- 关联具体触发变更的聊天记录的ID
                                          timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 为 user_character_favor_log 的前两个字段创建联合索引
CREATE INDEX idx_user_character_favor_log_world_char
    ON user_character_favor_log (user_world_id, character_id);

-- 10. 创建 user_event_log 表
CREATE TABLE user_event_log (
                                id BIGSERIAL PRIMARY KEY,
                                user_world_id BIGINT NOT NULL REFERENCES user_world_prefix(id) ON DELETE CASCADE,
                                event TEXT,
                                visible_characters BIGINT[], -- 存储可见角色的ID数组
                                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                event_vector vector(1024) -- 事件的向量
);

-- 【关键性能优化】为 visible_characters 数组创建 GIN 索引。
-- 这将使得类似： "SELECT * FROM user_event_log WHERE visible_characters @> ARRAY[1::BIGINT]" (判断数组内是否包含1) 的查询极其高效。
CREATE INDEX idx_user_event_log_visible_chars
    ON user_event_log USING GIN (visible_characters);