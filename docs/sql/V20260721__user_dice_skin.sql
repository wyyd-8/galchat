-- 用户选择的骰子皮肤标识，由前端据此选择展示资源。

ALTER TABLE user_info
    ADD COLUMN IF NOT EXISTS dice_skin VARCHAR(50) NOT NULL DEFAULT 'default';
