UPDATE coc_character_weapon
SET can_impale = TRUE
WHERE name IN (
    '手斧/镰刀',
    '大型刀具（骑兵军刀等）',
    '中型刀具（切肉刀等）',
    '小型刀具（折叠刀等）',
    '矛（骑枪）',
    '大型刀剑（马刀）',
    '中型刀剑（长剑、重剑）',
    '轻型刀剑（花剑、剑杖）',
    '伐木斧'
);
