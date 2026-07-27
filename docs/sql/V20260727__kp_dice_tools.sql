-- KP 掷骰工具升级脚本。当前项目未启用 Flyway，请在部署前手动执行一次。

ALTER TABLE dice_roll_result
    ADD COLUMN IF NOT EXISTS resolution_data JSONB NOT NULL
        DEFAULT '{"version":1,"type":"LEGACY","rule":{},"outcome":null,"effect":null}'::jsonb,
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP;

UPDATE dice_roll_result
SET resolved_at = updated_at
WHERE resolved_at IS NULL
  AND (result_data ->> 'result') IS NOT NULL;

ALTER TABLE group_reply_plan_item
    ALTER COLUMN actor_id DROP NOT NULL;
