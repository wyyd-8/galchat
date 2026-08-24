ALTER TABLE trpg_combat
    ADD COLUMN IF NOT EXISTS quick_npc_specs JSONB NOT NULL
        DEFAULT '[]'::jsonb;
