ALTER TABLE group_conversation
    ADD COLUMN game_day_no INT,
    ADD COLUMN game_time_period VARCHAR(20),
    ADD COLUMN game_time_revision INT NOT NULL DEFAULT 0,
    ADD COLUMN game_time_changed_step_id BIGINT,
    ADD COLUMN game_time_updated_at TIMESTAMP;

ALTER TABLE group_conversation
    ADD CONSTRAINT ck_group_conversation_game_time CHECK (
        (game_day_no IS NULL AND game_time_period IS NULL)
        OR (game_day_no >= 1 AND game_time_period IN (
            'DAWN', 'MORNING', 'NOON', 'AFTERNOON',
            'EVENING', 'LATE_NIGHT'))
    );
