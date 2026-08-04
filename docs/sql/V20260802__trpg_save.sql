-- 跑团独立存档。项目当前未启用 Flyway，请在部署前手动执行一次。
-- 每个 TRPG 群聊只保留一个记录点，读档仅影响该 conversation_id。

CREATE TABLE IF NOT EXISTS trpg_save (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    remark VARCHAR(200),
    saved_at TIMESTAMP NOT NULL,
    format_version INT NOT NULL,
    snapshot JSONB NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_trpg_save_conversation
    ON trpg_save (conversation_id);

CREATE INDEX IF NOT EXISTS idx_trpg_save_user
    ON trpg_save (user_id, saved_at DESC);
