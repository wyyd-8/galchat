ALTER TABLE trpg_auto_save
    ADD COLUMN checkpoint_type VARCHAR(16) NOT NULL DEFAULT 'TURN';

ALTER TABLE trpg_auto_save
    DROP CONSTRAINT trpg_auto_save_pkey;

ALTER TABLE trpg_auto_save
    ADD PRIMARY KEY (conversation_id, checkpoint_type);

ALTER TABLE trpg_auto_save
    ALTER COLUMN checkpoint_type DROP DEFAULT;

ALTER TABLE trpg_auto_save
    ADD CONSTRAINT trpg_auto_save_checkpoint_type_check
        CHECK (checkpoint_type IN ('TURN', 'SCENE', 'INITIAL'));
