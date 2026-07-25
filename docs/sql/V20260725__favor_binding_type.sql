ALTER TABLE user_character_favor_log
    ADD COLUMN IF NOT EXISTS binding_type VARCHAR(32);

UPDATE user_character_favor_log
SET binding_type = 'SINGLE_MESSAGE'
WHERE binding_type IS NULL;

ALTER TABLE user_character_favor_log
    ALTER COLUMN binding_type SET NOT NULL;

ALTER TABLE user_character_favor_log
    DROP CONSTRAINT IF EXISTS ck_user_character_favor_log_binding_type;

ALTER TABLE user_character_favor_log
    ADD CONSTRAINT ck_user_character_favor_log_binding_type
        CHECK (binding_type IN ('SINGLE_MESSAGE', 'GROUP_REPLY_STEP'));

DROP INDEX IF EXISTS idx_binding_chat;
DROP INDEX IF EXISTS idx_user_character_favor_log_world_character;

CREATE INDEX IF NOT EXISTS idx_user_character_favor_log_binding
    ON user_character_favor_log
       (user_world_id, character_id, binding_type, binding_chat);
