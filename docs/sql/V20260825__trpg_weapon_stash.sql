CREATE TABLE IF NOT EXISTS trpg_weapon_stash (
    weapon_id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    source_character_name VARCHAR(255) NOT NULL,
    location_name VARCHAR(500) NOT NULL,
    stash_reason VARCHAR(30) NOT NULL,
    weapon_snapshot JSONB NOT NULL,
    stashed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_trpg_weapon_stash_run
    ON trpg_weapon_stash (run_id, weapon_id);
