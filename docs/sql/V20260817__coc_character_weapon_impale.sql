ALTER TABLE coc_character_weapon
    ADD COLUMN IF NOT EXISTS can_impale BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE coc_character_weapon
SET can_impale = TRUE
WHERE skill_name LIKE '射击:%'
  AND damage ~* '[0-9]+D[0-9]+'
  AND damage NOT LIKE '%眩晕%';
