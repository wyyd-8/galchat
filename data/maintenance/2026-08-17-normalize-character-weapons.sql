BEGIN;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM coc_character_weapon
        WHERE name NOT IN (
            '伐木斧', '伸缩警棍', '匕首', '匕首或短镰刀', '双爪',
            '大型刀具（骑兵军刀等）', '小型刀具（折叠刀等）',
            '徒手攻击', '手枪', '掌爪与牙齿', '斗殴', '步枪',
            '脊刺', '训练锤', '霰弹枪', '.30-06栓动式步枪',
            '.38/9mm自动手枪'
        )
    ) THEN
        RAISE EXCEPTION '存在未纳入修正规则的武器名称，已取消更新';
    END IF;
END $$;

WITH catalog_match(
    imported_name, skill_name, damage, range_value, attacks_per_round,
    ammo_capacity, malfunction, can_impale, abnormal, risk_tags
) AS (
    VALUES
        ('伐木斧', '格斗:斧', '1D8+2+DB', '接触', '1',
         NULL::integer, NULL::text, true, true,
         '["显眼", "笨重"]'::jsonb),
        ('伸缩警棍', '斗殴', '1D6+DB', '接触', '1',
         NULL, NULL, false, false, '[]'::jsonb),
        ('匕首', '斗殴', '1D4+2+DB', '接触', '1',
         NULL, NULL, true, false, '[]'::jsonb),
        ('匕首或短镰刀', '斗殴', '1D4+2+DB', '接触', '1',
         NULL, NULL, true, false, '[]'::jsonb),
        ('大型刀具（骑兵军刀等）', '斗殴', '1D8+DB', '接触', '1',
         NULL, NULL, true, false, '["显眼"]'::jsonb),
        ('小型刀具（折叠刀等）', '斗殴', '1D4+DB', '接触', '1',
         NULL, NULL, true, false, '[]'::jsonb),
        ('训练锤', '斗殴', '1D6+DB', '接触', '1',
         NULL, NULL, false, false, '[]'::jsonb),
        ('手枪', '射击:手枪', '1D10', '15m', '1（3）',
         8, '99', true, false, '["高噪声"]'::jsonb),
        ('步枪', '射击:步枪/霰弹枪', '1D6+1', '30m', '1',
         6, '99', true, true, '["显眼", "高噪声", "笨重"]'::jsonb),
        ('霰弹枪', '射击:步枪/霰弹枪', '4D6/2D6/1D6',
         '近≤10m；中≤20m；远≤50m', '1或2',
         2, '100', true, true, '["显眼", "高噪声", "笨重"]'::jsonb),
        ('.30-06栓动式步枪', '射击:步枪/霰弹枪', '2D6+4',
         '110m', '1', 5, '100', true, true,
         '["显眼", "高噪声", "笨重"]'::jsonb),
        ('.38/9mm自动手枪', '射击:手枪', '1D10', '15m', '1（3）',
         8, '99', true, false, '["高噪声"]'::jsonb)
), updated AS (
    UPDATE coc_character_weapon AS weapon
    SET skill_name = match.skill_name,
        damage = match.damage,
        range = match.range_value,
        attacks_per_round = match.attacks_per_round,
        ammo_capacity = match.ammo_capacity,
        remaining_ammo = CASE
            WHEN match.ammo_capacity IS NULL THEN NULL
            ELSE LEAST(
                GREATEST(COALESCE(weapon.remaining_ammo,
                                  match.ammo_capacity), 0),
                match.ammo_capacity)
        END,
        malfunction = match.malfunction,
        can_impale = match.can_impale,
        is_broken = COALESCE(weapon.is_broken, false),
        abnormal = match.abnormal,
        risk_tags = match.risk_tags
    FROM catalog_match AS match
    WHERE weapon.name = match.imported_name
    RETURNING weapon.id
)
SELECT count(*) AS normalized_catalog_weapons FROM updated;

WITH updated AS (
    UPDATE coc_character_weapon
    SET skill_name = CASE
            WHEN name = '脊刺' THEN '格斗'
            ELSE '斗殴'
        END,
        range = CASE
            WHEN name = '脊刺' THEN COALESCE(NULLIF(BTRIM(range), ''),
                                             '近战或投射')
            ELSE '接触'
        END,
        attacks_per_round = COALESCE(NULLIF(BTRIM(attacks_per_round), ''),
                                     '1'),
        ammo_capacity = NULL,
        remaining_ammo = NULL,
        malfunction = NULL,
        can_impale = (name = '脊刺'),
        is_broken = COALESCE(is_broken, false)
    WHERE name IN ('徒手攻击', '斗殴', '掌爪与牙齿', '双爪', '脊刺')
    RETURNING id
)
SELECT count(*) AS normalized_custom_melee_weapons FROM updated;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM coc_character_weapon
        WHERE skill_name IS NULL
           OR damage IS NULL
           OR range IS NULL OR BTRIM(range) = ''
           OR attacks_per_round IS NULL OR BTRIM(attacks_per_round) = ''
           OR is_broken IS NULL
    ) THEN
        RAISE EXCEPTION '修正后仍有武器缺少通用字段，已回滚';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM coc_character_weapon
        WHERE name IN (
            '伐木斧', '伸缩警棍', '匕首', '匕首或短镰刀', '双爪',
            '大型刀具（骑兵军刀等）', '小型刀具（折叠刀等）',
            '徒手攻击', '掌爪与牙齿', '斗殴', '脊刺', '训练锤'
        )
          AND (ammo_capacity IS NOT NULL
               OR remaining_ammo IS NOT NULL
               OR malfunction IS NOT NULL)
    ) THEN
        RAISE EXCEPTION '近战武器仍残留远程武器字段，已回滚';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM coc_character_weapon
        WHERE name IN (
            '手枪', '步枪', '霰弹枪', '.30-06栓动式步枪',
            '.38/9mm自动手枪'
        )
          AND (ammo_capacity IS NULL
               OR remaining_ammo IS NULL
               OR malfunction IS NULL)
    ) THEN
        RAISE EXCEPTION '远程武器仍缺少弹药或故障字段，已回滚';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM coc_character_weapon
        WHERE abnormal IS DISTINCT FROM
              (jsonb_array_length(COALESCE(risk_tags, '[]'::jsonb)) >= 2)
    ) THEN
        RAISE EXCEPTION 'abnormal 与 riskTags 数量不一致，已回滚';
    END IF;
END $$;

\if :{?dry_run}
ROLLBACK;
\else
COMMIT;
\endif
