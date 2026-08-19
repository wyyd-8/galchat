ALTER TABLE coc_character
    ADD COLUMN in_cover BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN cover_action_forfeit_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stunned_remaining_rounds INT NOT NULL DEFAULT 0,
    ADD COLUMN restrained_by_character_id BIGINT,
    ADD COLUMN melee_attacked_this_round BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE coc_character
    ADD CONSTRAINT ck_coc_character_stunned_remaining_rounds
        CHECK (stunned_remaining_rounds >= 0),
    ADD CONSTRAINT fk_coc_character_restrained_by
        FOREIGN KEY (restrained_by_character_id)
        REFERENCES coc_character (id)
        ON DELETE SET NULL
        DEFERRABLE INITIALLY DEFERRED;
