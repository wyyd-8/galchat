\set ON_ERROR_STOP on

BEGIN;

UPDATE group_reply_plan_item item
SET subject_character_id = card.id,
    updated_at = CURRENT_TIMESTAMP
FROM group_reply_plan plan
JOIN coc_character card
  ON card.run_id = plan.conversation_id
WHERE item.plan_id = plan.id
  AND plan.source = 'SCENE'
  AND item.subject_character_id IS NULL
  AND (
      item.actor_type = 'user'
      AND card.actor_type = 'PLAYER'
      AND card.participant_id IS NULL
      AND card.id = item.actor_id
      OR item.actor_type = 'character'
      AND card.actor_type = 'BOT'
      AND card.participant_id = item.actor_id
  );

UPDATE group_chat_reply_step step
SET subject_character_id = card.id,
    updated_at = CURRENT_TIMESTAMP
FROM group_chat_turn turn
JOIN coc_character card
  ON card.run_id = turn.conversation_id
WHERE step.turn_id = turn.id
  AND turn.plan_source = 'SCENE'
  AND step.subject_character_id IS NULL
  AND (
      step.speaker_type = 'user'
      AND card.actor_type = 'PLAYER'
      AND card.participant_id IS NULL
      AND card.id = step.speaker_id
      OR step.speaker_type = 'character'
      AND card.actor_type = 'BOT'
      AND card.participant_id = step.speaker_id
  );

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM group_reply_plan_item item
        JOIN group_reply_plan plan ON plan.id = item.plan_id
        WHERE plan.source = 'SCENE'
          AND item.actor_type IN ('user', 'character')
          AND item.subject_character_id IS NULL
    ) THEN
        RAISE EXCEPTION 'scene investigator plan item could not be bound to a character card';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM group_chat_reply_step step
        JOIN group_chat_turn turn ON turn.id = step.turn_id
        WHERE turn.plan_source = 'SCENE'
          AND step.speaker_type IN ('user', 'character')
          AND step.subject_character_id IS NULL
    ) THEN
        RAISE EXCEPTION 'scene reply step could not be bound to a character card';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM group_reply_plan_item item
        JOIN group_reply_plan plan ON plan.id = item.plan_id
        JOIN coc_character card ON card.id = item.subject_character_id
        WHERE plan.source = 'SCENE'
          AND item.actor_type IN ('user', 'character')
          AND (
              card.run_id <> plan.conversation_id
              OR item.actor_type = 'user'
                 AND (card.actor_type <> 'PLAYER'
                      OR card.participant_id IS NOT NULL
                      OR card.id <> item.actor_id)
              OR item.actor_type = 'character'
                 AND (card.actor_type <> 'BOT'
                      OR card.participant_id <> item.actor_id)
          )
    ) THEN
        RAISE EXCEPTION 'scene investigator plan item has an invalid character-card binding';
    END IF;
END
$$;

CREATE TEMP TABLE migrated_trpg_plan_bindings (
    source_table TEXT NOT NULL,
    row_id BIGINT NOT NULL,
    snapshot JSONB NOT NULL
) ON COMMIT DROP;

INSERT INTO migrated_trpg_plan_bindings (
    source_table, row_id, snapshot)
SELECT source_table,
       row_id,
       jsonb_set(
           snapshot,
           '{replyPlanItems}',
           COALESCE((
               SELECT jsonb_agg(
                   CASE
                       WHEN plan->>'source' = 'SCENE'
                            AND item->>'actorType' IN ('user', 'character')
                            AND NULLIF(item->>'subjectCharacterId', '') IS NULL
                           THEN item || jsonb_build_object(
                               'subjectCharacterId', (
                                   SELECT (card->>'id')::BIGINT
                                   FROM jsonb_array_elements(
                                       COALESCE(snapshot->'characters', '[]'::jsonb)
                                   ) AS cards(card)
                                   WHERE (
                                       item->>'actorType' = 'user'
                                       AND card->>'actorType' = 'PLAYER'
                                       AND NULLIF(card->>'participantId', '') IS NULL
                                       AND (card->>'id')::BIGINT =
                                           (item->>'actorId')::BIGINT
                                       OR item->>'actorType' = 'character'
                                       AND card->>'actorType' = 'BOT'
                                       AND (card->>'participantId')::BIGINT =
                                           (item->>'actorId')::BIGINT
                                   )
                                   LIMIT 1
                               ))
                       ELSE item
                   END
                   ORDER BY item_order
               )
               FROM jsonb_array_elements(
                   COALESCE(snapshot->'replyPlanItems', '[]'::jsonb)
               ) WITH ORDINALITY AS items(item, item_order)
               LEFT JOIN LATERAL (
                   SELECT candidate AS plan
                   FROM jsonb_array_elements(
                       COALESCE(snapshot->'replyPlans', '[]'::jsonb)
                   ) AS plans(candidate)
                   WHERE candidate->>'id' = item->>'planId'
                   LIMIT 1
               ) matched_plan ON TRUE
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
        FROM migrated_trpg_plan_bindings migrated
        CROSS JOIN LATERAL jsonb_array_elements(
            COALESCE(migrated.snapshot->'replyPlanItems', '[]'::jsonb)
        ) AS items(item)
        JOIN LATERAL (
            SELECT candidate AS plan
            FROM jsonb_array_elements(
                COALESCE(migrated.snapshot->'replyPlans', '[]'::jsonb)
            ) AS plans(candidate)
            WHERE candidate->>'id' = item->>'planId'
            LIMIT 1
        ) matched_plan ON TRUE
        WHERE plan->>'source' = 'SCENE'
          AND item->>'actorType' IN ('user', 'character')
          AND NULLIF(item->>'subjectCharacterId', '') IS NULL
    ) THEN
        RAISE EXCEPTION 'saved scene investigator item could not be bound to a character card';
    END IF;
END
$$;

UPDATE trpg_save save
SET snapshot = migrated.snapshot
FROM migrated_trpg_plan_bindings migrated
WHERE migrated.source_table = 'trpg_save'
  AND migrated.row_id = save.id;

UPDATE trpg_auto_save save
SET snapshot = migrated.snapshot
FROM migrated_trpg_plan_bindings migrated
WHERE migrated.source_table = 'trpg_auto_save'
  AND migrated.row_id = save.conversation_id;

COMMIT;
