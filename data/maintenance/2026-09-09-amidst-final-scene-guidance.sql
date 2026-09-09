-- 为已导入的《古树林中》第三天场景增加最终场景说明。
-- 仅替换原有结束提示，不修改跑团消息、计划、进度或其他模组内容。
-- 默认演练并回滚；提交时传入 -v commit_migration=true。
-- psql -d postgres -v commit_migration=true -f data/maintenance/2026-09-09-amidst-final-scene-guidance.sql
\set ON_ERROR_STOP on
\if :{?commit_migration}
\else
\set commit_migration false
\endif

BEGIN;
SET LOCAL lock_timeout = '5s';

DO $migration$
DECLARE
    location_id BIGINT;
    original_content TEXT;
    old_guidance CONSTANT TEXT := $old_guidance$> 何时结束当前场景：所有仍在进行的地点探索、战斗、营救和分头行动均已结算，各调查员的最终去向明确，而且珍妮与其他俘虏、挖掘现场、水晶运输及格拉基计划已经形成可公开说明的结果时结束。不得仅因时间线写有“第三日日落后”就跳过调查员尚未完成的行动或自动判定仪式成功。
> 本场景是模组最后一个主场景，没有预设的下一场景。达到上述结束条件后，KP可以结束当前场景，并在模组结果已经完整确定时结束整个跑团；如果调查员仍有合理的后续行动，就继续本场景或在选景阶段自由选择合适场景。$old_guidance$;
    new_guidance CONSTANT TEXT := $new_guidance$>
> 【最终场景说明｜非剧情内容】
>
> 本场景为模组最终主场景，没有预设的下一场景。
> 完结条件：珍妮与其他俘虏的命运已经确定，调查员对挖掘现场及水晶运输的干预已有结果，且队伍已脱离即时危险、决定结束调查时，应收束正篇。营救成功、阻止或延迟格拉基的计划，以及营救失败后撤离，都可以形成结局；根据已公开的结果判断，不要求等到第三日日落或亲眼见证仪式。
> 不必完成的事项：不要求探索全部地点、取得所有线索、消灭所有仆从或彻底摧毁水晶；不得为补齐这些事项继续延长正篇。
> 收尾范围：安全返镇、移交获救者、常规就医和领取悬赏属于收尾。没有尚待解决的实质危险或影响结局的关键选择时，依据调查员已声明的行动简短结算，不为这些事项另开子场景，不反复要求调查员声明赶路或跟随；收尾后调用finishRun，人物后传交由系统处理。
> 继续条件：若仍有影响结局的即时危险、未完成的营救或分头行动，或玩家明确选择继续调查，则继续主持相关内容。不得代替调查员选择撤离、放弃营救或继续调查，也不得仅因时间线写有“第三日日落后”就跳过尚未完成的行动或自动判定仪式成功。$new_guidance$;
BEGIN
    SELECT location.id, location.content INTO STRICT location_id, original_content
    FROM coc_module_location location
    JOIN coc_module module ON module.id = location.module_id
    WHERE module.name = '古树林中' AND location.name = '第三天场景'
    FOR UPDATE OF location;

    IF strpos(original_content, new_guidance) > 0
            AND strpos(original_content, old_guidance) = 0 THEN
        RAISE NOTICE '最终场景说明已存在，无需重复更新';
        RETURN;
    END IF;
    IF original_content IS NULL
            OR strpos(original_content, old_guidance) = 0
            OR length(original_content) - length(replace(original_content, old_guidance, '')) <> length(old_guidance)
            OR strpos(original_content, '【最终场景说明') > 0 THEN
        RAISE EXCEPTION '场景结束提示已变化或不唯一，未执行更新';
    END IF;

    UPDATE coc_module_location
    SET content = replace(original_content, old_guidance, new_guidance)
    WHERE id = location_id;
    RAISE NOTICE '已更新最终场景说明，场景ID=%', location_id;
END
$migration$;

\if :commit_migration
COMMIT;
\else
ROLLBACK;
\endif
