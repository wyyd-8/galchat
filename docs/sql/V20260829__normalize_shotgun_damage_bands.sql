UPDATE coc_character_weapon
SET damage = CASE damage
    WHEN '近4D6；中2D6；远1D6' THEN '4D6/2D6/1D6'
    WHEN '近4D6；中1D6；远无效' THEN '4D6/1D6/0'
    ELSE damage
END
WHERE damage IN (
    '近4D6；中2D6；远1D6',
    '近4D6；中1D6；远无效'
);

UPDATE trpg_weapon_stash
SET weapon_snapshot = jsonb_set(
        weapon_snapshot,
        '{damage}',
        to_jsonb(CASE weapon_snapshot ->> 'damage'
            WHEN '近4D6；中2D6；远1D6' THEN '4D6/2D6/1D6'
            WHEN '近4D6；中1D6；远无效' THEN '4D6/1D6/0'
        END),
        FALSE)
WHERE weapon_snapshot ->> 'damage' IN (
    '近4D6；中2D6；远1D6',
    '近4D6；中1D6；远无效'
);

UPDATE coc_module_character AS template
SET card_data = jsonb_set(
        template.card_data,
        '{weapons}',
        (
            SELECT jsonb_agg(
                    CASE weapon ->> 'damage'
                        WHEN '近4D6；中2D6；远1D6'
                            THEN jsonb_set(weapon, '{damage}',
                                    '"4D6/2D6/1D6"'::jsonb, FALSE)
                        WHEN '近4D6；中1D6；远无效'
                            THEN jsonb_set(weapon, '{damage}',
                                    '"4D6/1D6/0"'::jsonb, FALSE)
                        ELSE weapon
                    END
                    ORDER BY ordinal)
            FROM jsonb_array_elements(template.card_data -> 'weapons')
                    WITH ORDINALITY AS entries(weapon, ordinal)
        ),
        FALSE)
WHERE jsonb_typeof(template.card_data -> 'weapons') = 'array'
  AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(template.card_data -> 'weapons') AS weapon
      WHERE weapon ->> 'damage' IN (
          '近4D6；中2D6；远1D6',
          '近4D6；中1D6；远无效'
      )
  );

UPDATE trpg_save AS saved
SET snapshot = jsonb_set(
        saved.snapshot,
        '{characterWeapons}',
        (
            SELECT jsonb_agg(
                    CASE weapon ->> 'damage'
                        WHEN '近4D6；中2D6；远1D6'
                            THEN jsonb_set(weapon, '{damage}',
                                    '"4D6/2D6/1D6"'::jsonb, FALSE)
                        WHEN '近4D6；中1D6；远无效'
                            THEN jsonb_set(weapon, '{damage}',
                                    '"4D6/1D6/0"'::jsonb, FALSE)
                        ELSE weapon
                    END
                    ORDER BY ordinal)
            FROM jsonb_array_elements(saved.snapshot -> 'characterWeapons')
                    WITH ORDINALITY AS entries(weapon, ordinal)
        ),
        FALSE)
WHERE jsonb_typeof(saved.snapshot -> 'characterWeapons') = 'array'
  AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(
              saved.snapshot -> 'characterWeapons') AS weapon
      WHERE weapon ->> 'damage' IN (
          '近4D6；中2D6；远1D6',
          '近4D6；中1D6；远无效'
      )
  );

UPDATE trpg_save AS saved
SET snapshot = jsonb_set(
        saved.snapshot,
        '{weaponStash}',
        (
            SELECT jsonb_agg(
                    CASE weapon #>> '{weaponSnapshot,damage}'
                        WHEN '近4D6；中2D6；远1D6'
                            THEN jsonb_set(weapon,
                                    '{weaponSnapshot,damage}',
                                    '"4D6/2D6/1D6"'::jsonb, FALSE)
                        WHEN '近4D6；中1D6；远无效'
                            THEN jsonb_set(weapon,
                                    '{weaponSnapshot,damage}',
                                    '"4D6/1D6/0"'::jsonb, FALSE)
                        ELSE weapon
                    END
                    ORDER BY ordinal)
            FROM jsonb_array_elements(saved.snapshot -> 'weaponStash')
                    WITH ORDINALITY AS entries(weapon, ordinal)
        ),
        FALSE)
WHERE jsonb_typeof(saved.snapshot -> 'weaponStash') = 'array'
  AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(saved.snapshot -> 'weaponStash') AS weapon
      WHERE weapon #>> '{weaponSnapshot,damage}' IN (
          '近4D6；中2D6；远1D6',
          '近4D6；中1D6；远无效'
      )
  );

UPDATE trpg_auto_save AS saved
SET snapshot = jsonb_set(
        saved.snapshot,
        '{characterWeapons}',
        (
            SELECT jsonb_agg(
                    CASE weapon ->> 'damage'
                        WHEN '近4D6；中2D6；远1D6'
                            THEN jsonb_set(weapon, '{damage}',
                                    '"4D6/2D6/1D6"'::jsonb, FALSE)
                        WHEN '近4D6；中1D6；远无效'
                            THEN jsonb_set(weapon, '{damage}',
                                    '"4D6/1D6/0"'::jsonb, FALSE)
                        ELSE weapon
                    END
                    ORDER BY ordinal)
            FROM jsonb_array_elements(saved.snapshot -> 'characterWeapons')
                    WITH ORDINALITY AS entries(weapon, ordinal)
        ),
        FALSE)
WHERE jsonb_typeof(saved.snapshot -> 'characterWeapons') = 'array'
  AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(
              saved.snapshot -> 'characterWeapons') AS weapon
      WHERE weapon ->> 'damage' IN (
          '近4D6；中2D6；远1D6',
          '近4D6；中1D6；远无效'
      )
  );

UPDATE trpg_auto_save AS saved
SET snapshot = jsonb_set(
        saved.snapshot,
        '{weaponStash}',
        (
            SELECT jsonb_agg(
                    CASE weapon #>> '{weaponSnapshot,damage}'
                        WHEN '近4D6；中2D6；远1D6'
                            THEN jsonb_set(weapon,
                                    '{weaponSnapshot,damage}',
                                    '"4D6/2D6/1D6"'::jsonb, FALSE)
                        WHEN '近4D6；中1D6；远无效'
                            THEN jsonb_set(weapon,
                                    '{weaponSnapshot,damage}',
                                    '"4D6/1D6/0"'::jsonb, FALSE)
                        ELSE weapon
                    END
                    ORDER BY ordinal)
            FROM jsonb_array_elements(saved.snapshot -> 'weaponStash')
                    WITH ORDINALITY AS entries(weapon, ordinal)
        ),
        FALSE)
WHERE jsonb_typeof(saved.snapshot -> 'weaponStash') = 'array'
  AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(
              saved.snapshot -> 'weaponStash') AS weapon
      WHERE weapon #>> '{weaponSnapshot,damage}' IN (
          '近4D6；中2D6；远1D6',
          '近4D6；中1D6；远无效'
      )
  );
