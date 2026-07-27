-- 跑团掷骰概要与逐角色结果。当前项目未启用 Flyway，请在部署前手动执行一次。

CREATE TABLE IF NOT EXISTS dice_roll_summary (
    id BIGSERIAL PRIMARY KEY,
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
CREATE INDEX IF NOT EXISTS idx_dice_roll_summary_conversation
    ON dice_roll_summary (conversation_id, id);

CREATE TABLE IF NOT EXISTS dice_roll_result (
    id BIGSERIAL PRIMARY KEY,
    summary_id BIGINT NOT NULL,
    character_id BIGINT,
    round_no INT NOT NULL,
    display_order INT NOT NULL,
    display_type VARCHAR(20),
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
CREATE UNIQUE INDEX IF NOT EXISTS uk_dice_roll_result_position
    ON dice_roll_result (summary_id, round_no, display_order);
CREATE INDEX IF NOT EXISTS idx_dice_roll_result_summary
    ON dice_roll_result (summary_id, round_no, display_order, id);
