ALTER TABLE coc_module
    ADD COLUMN owner_user_id BIGINT,
    ADD COLUMN edit_locked BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_coc_module_owner_visible
    ON coc_module (owner_user_id, visible, id);
