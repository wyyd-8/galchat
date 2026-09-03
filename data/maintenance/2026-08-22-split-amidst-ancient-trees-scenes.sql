-- 将已导入的《古树林中》更新为七个时间场景，并把依赖该模组的跑团
-- 重置到“角色卡已绑定、尚未开始选景”的状态。
--
-- 默认只演练并回滚：
--   psql -d postgres -f data/maintenance/2026-08-22-split-amidst-ancient-trees-scenes.sql
-- 确认输出无误后提交：
--   psql -d postgres -v commit_migration=true \
--     -f data/maintenance/2026-08-22-split-amidst-ancient-trees-scenes.sql
--
-- PostgreSQL 提交后还需清除这些跑团的 trpg:group:* Redis 派生状态。

\set ON_ERROR_STOP on
\if :{?commit_migration}
\else
\set commit_migration false
\endif

BEGIN;

CREATE TEMP TABLE amidst_module ON COMMIT DROP AS
SELECT id
FROM coc_module
WHERE name = '古树林中';

DO $check$
BEGIN
    IF (SELECT COUNT(*) FROM amidst_module) <> 1 THEN
        RAISE EXCEPTION '迁移要求数据库中恰好存在一个“古树林中”模组';
    END IF;
    IF EXISTS (
        SELECT 1 FROM coc_module
        WHERE name = '古树林中（场景迁移中）'
    ) THEN
        RAISE EXCEPTION '临时模组名称已被占用';
    END IF;
END
$check$;

CREATE TEMP TABLE amidst_runs ON COMMIT DROP AS
SELECT conversation.id
FROM group_conversation conversation
JOIN amidst_module module_row
  ON module_row.id = conversation.module_id;

CREATE TEMP TABLE amidst_run_baseline ON COMMIT DROP AS
SELECT run.id AS run_id,
       (SELECT COUNT(*) FROM group_chat_member member_row
        WHERE member_row.conversation_id = run.id) AS member_count,
       (SELECT COUNT(*) FROM coc_character card
        WHERE card.run_id = run.id) AS card_count
FROM amidst_runs run;

-- 临时让出名称，再复用原始导入脚本生成与新导入完全一致的七个场景。
UPDATE coc_module
SET name = '古树林中（场景迁移中）',
    updated_at = CURRENT_TIMESTAMP
WHERE id = (SELECT id FROM amidst_module);

\ir ../modules/15-01-amidst-the-ancient-trees.sql

CREATE TEMP TABLE amidst_fresh_module ON COMMIT DROP AS
SELECT id
FROM coc_module
WHERE name = '古树林中';

DO $check$
BEGIN
    IF (SELECT COUNT(*) FROM amidst_fresh_module) <> 1 THEN
        RAISE EXCEPTION '原始导入脚本没有生成唯一的新版模组';
    END IF;
    IF (SELECT COUNT(*)
        FROM coc_module_location location
        JOIN amidst_fresh_module fresh ON fresh.id = location.module_id) <> 7 THEN
        RAISE EXCEPTION '新版模组场景数量不是7';
    END IF;
END
$check$;

-- 清除主题向量派生数据。
DELETE FROM group_topic_vector_store vector
WHERE EXISTS (
    SELECT 1 FROM amidst_runs run
    WHERE vector.metadata::jsonb ->> 'conversationId' = run.id::text
);

-- 先删除回复步骤和骰点的叶子记录，再清除行动轮与场景计划。
DELETE FROM group_chat_agent_decision decision
WHERE decision.reply_step_id IN (
    SELECT step.id
    FROM group_chat_reply_step step
    JOIN group_chat_turn turn_row ON turn_row.id = step.turn_id
    JOIN amidst_runs run ON run.id = turn_row.conversation_id
);

DELETE FROM group_chat_tool_call tool_call
WHERE tool_call.reply_step_id IN (
    SELECT step.id
    FROM group_chat_reply_step step
    JOIN group_chat_turn turn_row ON turn_row.id = step.turn_id
    JOIN amidst_runs run ON run.id = turn_row.conversation_id
);

DELETE FROM dice_roll_result result
WHERE result.summary_id IN (
    SELECT summary.id
    FROM dice_roll_summary summary
    JOIN amidst_runs run ON run.id = summary.conversation_id
);

DELETE FROM dice_roll_summary summary
USING amidst_runs run
WHERE summary.conversation_id = run.id;

DELETE FROM group_turn_checkpoint checkpoint
USING amidst_runs run
WHERE checkpoint.conversation_id = run.id;

DELETE FROM group_chat_message message
USING amidst_runs run
WHERE message.conversation_id = run.id;

DELETE FROM group_chat_reply_step step
WHERE step.turn_id IN (
    SELECT turn_row.id
    FROM group_chat_turn turn_row
    JOIN amidst_runs run ON run.id = turn_row.conversation_id
);

DELETE FROM group_chat_turn turn_row
USING amidst_runs run
WHERE turn_row.conversation_id = run.id;

DELETE FROM trpg_combat combat
USING amidst_runs run
WHERE combat.conversation_id = run.id;

DELETE FROM trpg_runtime_child_scene child
USING amidst_runs run
WHERE child.conversation_id = run.id;

DELETE FROM group_reply_plan_item item
WHERE item.plan_id IN (
    SELECT plan.id
    FROM group_reply_plan plan
    JOIN amidst_runs run ON run.id = plan.conversation_id
);

DELETE FROM group_reply_plan plan
USING amidst_runs run
WHERE plan.conversation_id = run.id;

DELETE FROM group_context_summary summary
USING amidst_runs run
WHERE summary.conversation_id = run.id;

DELETE FROM group_chat_topic topic
USING amidst_runs run
WHERE topic.conversation_id = run.id;

DELETE FROM trpg_auto_save save
USING amidst_runs run
WHERE save.conversation_id = run.id;

DELETE FROM trpg_save save
USING amidst_runs run
WHERE save.conversation_id = run.id;

-- 保留人物卡本体、数值、技能、装备和参与者绑定，只清除剧情速记及战斗临时标记。
UPDATE coc_character card
SET quick_notes = NULL,
    in_cover = FALSE,
    cover_action_forfeit_pending = FALSE,
    stunned_remaining_rounds = 0,
    restrained_by_character_id = NULL,
    melee_attacked_this_round = FALSE,
    updated_at = CURRENT_TIMESTAMP
FROM amidst_runs run
WHERE card.run_id = run.id;

-- 恢复新建TRPG群聊在人物卡绑定完成后的会话状态。
UPDATE group_conversation conversation
SET active_reply_plan_id = NULL,
    summary = NULL,
    status = 'active',
    version = 0,
    game_day_no = NULL,
    game_time_period = NULL,
    game_time_revision = 0,
    game_time_changed_step_id = NULL,
    game_time_updated_at = NULL,
    closed_at = NULL,
    updated_at = CURRENT_TIMESTAMP
FROM amidst_runs run
WHERE conversation.id = run.id;

-- 旧场景已无运行时引用；把新版场景移动到原模组ID，保持所有跑团绑定不变。
DELETE FROM coc_module_location location
USING amidst_module module_row
WHERE location.module_id = module_row.id;

UPDATE coc_module_location location
SET module_id = (SELECT id FROM amidst_module),
    updated_at = CURRENT_TIMESTAMP
WHERE location.module_id = (SELECT id FROM amidst_fresh_module);

-- 删除只为生成新版场景而临时导入的其余模组数据。
DELETE FROM coc_module_context context_row
USING amidst_fresh_module fresh
WHERE context_row.module_id = fresh.id;

DELETE FROM coc_module_clue clue
USING amidst_fresh_module fresh
WHERE clue.module_id = fresh.id;

DELETE FROM coc_module_material material
USING amidst_fresh_module fresh
WHERE material.module_id = fresh.id;

DELETE FROM coc_module_character character_template
USING amidst_fresh_module fresh
WHERE character_template.module_id = fresh.id;

DELETE FROM coc_module fresh_module
USING amidst_fresh_module fresh
WHERE fresh_module.id = fresh.id;

UPDATE coc_module
SET name = '古树林中',
    updated_at = CURRENT_TIMESTAMP
WHERE id = (SELECT id FROM amidst_module);

-- 提交前验收：场景形状正确、进度清空、角色卡和群聊成员数量未变。
DO $verify$
BEGIN
    IF (SELECT COUNT(*) FROM coc_module WHERE name = '古树林中') <> 1 THEN
        RAISE EXCEPTION '迁移后模组名称不唯一';
    END IF;
    IF (SELECT COUNT(*)
        FROM coc_module_location location
        JOIN amidst_module module_row ON module_row.id = location.module_id) <> 7 THEN
        RAISE EXCEPTION '迁移后场景数量不是7';
    END IF;
    IF EXISTS (
        SELECT required.name
        FROM (VALUES
            ('第一天－上午'), ('第一天－下午'), ('第一天－夜间'),
            ('第二天－白天'), ('第二天－临近黄昏'), ('第二天－晚上'),
            ('第三天场景')
        ) AS required(name)
        WHERE NOT EXISTS (
            SELECT 1
            FROM coc_module_location location
            JOIN amidst_module module_row ON module_row.id = location.module_id
            WHERE location.name = required.name
              AND location.content LIKE '%【AI推进提示｜非剧情内容】%'
        )
    ) THEN
        RAISE EXCEPTION '迁移后的场景名称或AI推进提示不完整';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM group_conversation conversation
        JOIN amidst_runs run ON run.id = conversation.id
        WHERE conversation.active_reply_plan_id IS NOT NULL
           OR conversation.summary IS NOT NULL
           OR conversation.status <> 'active'
           OR conversation.version <> 0
           OR conversation.game_day_no IS NOT NULL
           OR conversation.game_time_period IS NOT NULL
           OR conversation.game_time_revision <> 0
           OR conversation.game_time_changed_step_id IS NOT NULL
           OR conversation.game_time_updated_at IS NOT NULL
           OR conversation.closed_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION '跑团会话未恢复到初始状态';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM amidst_run_baseline baseline
        WHERE baseline.member_count <> (
                SELECT COUNT(*) FROM group_chat_member member_row
                WHERE member_row.conversation_id = baseline.run_id)
           OR baseline.card_count <> (
                SELECT COUNT(*) FROM coc_character card
                WHERE card.run_id = baseline.run_id)
    ) THEN
        RAISE EXCEPTION '跑团成员或人物卡数量发生变化';
    END IF;
    IF EXISTS (
        SELECT 1 FROM coc_character card
        JOIN amidst_runs run ON run.id = card.run_id
        WHERE card.quick_notes IS NOT NULL
           OR COALESCE(card.in_cover, FALSE)
           OR COALESCE(card.cover_action_forfeit_pending, FALSE)
           OR COALESCE(card.stunned_remaining_rounds, 0) <> 0
           OR card.restrained_by_character_id IS NOT NULL
           OR COALESCE(card.melee_attacked_this_round, FALSE)
    ) THEN
        RAISE EXCEPTION '人物卡剧情速记或战斗临时标记未清除';
    END IF;
    IF EXISTS (SELECT 1 FROM group_chat_message message JOIN amidst_runs run ON run.id = message.conversation_id)
       OR EXISTS (SELECT 1 FROM group_chat_turn turn_row JOIN amidst_runs run ON run.id = turn_row.conversation_id)
       OR EXISTS (SELECT 1 FROM group_reply_plan plan JOIN amidst_runs run ON run.id = plan.conversation_id)
       OR EXISTS (SELECT 1 FROM group_context_summary summary JOIN amidst_runs run ON run.id = summary.conversation_id)
       OR EXISTS (SELECT 1 FROM trpg_combat combat JOIN amidst_runs run ON run.id = combat.conversation_id)
       OR EXISTS (SELECT 1 FROM trpg_auto_save save JOIN amidst_runs run ON run.id = save.conversation_id)
       OR EXISTS (SELECT 1 FROM trpg_save save JOIN amidst_runs run ON run.id = save.conversation_id) THEN
        RAISE EXCEPTION '跑团剧情进度或存档未完全清除';
    END IF;
END
$verify$;

SELECT module_row.id AS module_id,
       (SELECT COUNT(*) FROM amidst_runs) AS reset_run_count,
       (SELECT COUNT(*) FROM coc_module_location location
        WHERE location.module_id = module_row.id) AS scene_count
FROM amidst_module module_row;

\if :commit_migration
COMMIT;
\echo '已提交《古树林中》场景迁移和跑团重置。'
\else
ROLLBACK;
\echo '演练完成并已回滚；传入 -v commit_migration=true 才会提交。'
\endif
