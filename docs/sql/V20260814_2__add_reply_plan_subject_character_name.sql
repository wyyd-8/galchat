\set ON_ERROR_STOP on

BEGIN;

ALTER TABLE group_reply_plan_item
    ADD COLUMN IF NOT EXISTS subject_character_name VARCHAR(255);

ALTER TABLE group_reply_plan_item
    ALTER COLUMN subject_character_name TYPE VARCHAR(255);

UPDATE group_reply_plan_item item
SET subject_character_name = card.name,
    updated_at = CURRENT_TIMESTAMP
FROM group_reply_plan plan
JOIN coc_character card
  ON card.run_id = plan.conversation_id
WHERE item.plan_id = plan.id
  AND item.subject_character_id = card.id
  AND NULLIF(BTRIM(item.subject_character_name), '') IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM group_reply_plan_item item
        WHERE item.subject_character_id IS NOT NULL
          AND NULLIF(BTRIM(item.subject_character_name), '') IS NULL
    ) THEN
        RAISE EXCEPTION 'reply plan item with a character card is missing its name snapshot';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM group_reply_plan_item item
        JOIN group_reply_plan plan ON plan.id = item.plan_id
        JOIN coc_character card ON card.id = item.subject_character_id
        WHERE card.run_id <> plan.conversation_id
    ) THEN
        RAISE EXCEPTION 'reply plan item name snapshot refers to a card from another conversation';
    END IF;
END
$$;

CREATE TEMP TABLE migrated_trpg_plan_names (
    source_table TEXT NOT NULL,
    row_id BIGINT NOT NULL,
    snapshot JSONB NOT NULL
) ON COMMIT DROP;

INSERT INTO migrated_trpg_plan_names (
    source_table, row_id, snapshot)
SELECT source_table,
       row_id,
       jsonb_set(
           snapshot,
           '{replyPlanItems}',
           COALESCE((
               SELECT jsonb_agg(
                   CASE
                       WHEN NULLIF(item->>'subjectCharacterId', '')
                                IS NOT NULL
                           THEN item || jsonb_build_object(
                               'subjectCharacterName', (
                                   SELECT card->>'name'
                                   FROM jsonb_array_elements(
                                       COALESCE(
                                           snapshot->'characters',
                                           '[]'::jsonb)
                                   ) AS cards(card)
                                   WHERE card->>'id' =
                                         item->>'subjectCharacterId'
                                   LIMIT 1
                               ))
                       ELSE item - 'subjectCharacterName'
                   END
                   ORDER BY item_order
               )
               FROM jsonb_array_elements(
                   COALESCE(
                       snapshot->'replyPlanItems',
                       '[]'::jsonb)
               ) WITH ORDINALITY AS items(item, item_order)
           ), '[]'::jsonb),
           true
       )
FROM (
    SELECT 'trpg_save' AS source_table, id AS row_id, snapshot
    FROM trpg_save
    UNION ALL
    SELECT 'trpg_auto_save', conversation_id, snapshot
    FROM trpg_auto_save
) saves;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM migrated_trpg_plan_names migrated
        CROSS JOIN LATERAL jsonb_array_elements(
            COALESCE(
                migrated.snapshot->'replyPlanItems',
                '[]'::jsonb)
        ) AS items(item)
        WHERE NULLIF(item->>'subjectCharacterId', '') IS NOT NULL
          AND NULLIF(BTRIM(item->>'subjectCharacterName'), '') IS NULL
    ) THEN
        RAISE EXCEPTION 'saved reply plan item is missing its character name snapshot';
    END IF;
END
$$;

UPDATE trpg_save save
SET snapshot = migrated.snapshot
FROM migrated_trpg_plan_names migrated
WHERE migrated.source_table = 'trpg_save'
  AND migrated.row_id = save.id;

UPDATE trpg_auto_save save
SET snapshot = migrated.snapshot
FROM migrated_trpg_plan_names migrated
WHERE migrated.source_table = 'trpg_auto_save'
  AND migrated.row_id = save.conversation_id;

COMMIT;
