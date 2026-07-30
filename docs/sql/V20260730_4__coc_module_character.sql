CREATE TABLE IF NOT EXISTS coc_module_character (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL,
    sort_order INT NOT NULL,
    card_data JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_coc_module_character_module_order
    ON coc_module_character (module_id, sort_order, id);
