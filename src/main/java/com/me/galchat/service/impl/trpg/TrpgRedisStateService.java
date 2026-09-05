package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.service.ITrpgRedisStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TrpgRedisStateService implements ITrpgRedisStateService {

    public static final Duration TRANSIENT_TTL = Duration.ofDays(7);
    private static final Duration MATERIAL_TTL = Duration.ofDays(90);

    private final StringRedisTemplate redisTemplate;

    @Override
    public TrpgSaveSnapshotDTO.RedisStateSnapshot capture(
            Long conversationId, List<Long> sceneIds) {
        Set<Long> shownMaterialIds = new LinkedHashSet<>();
        for (String value : safeSet(redisTemplate.opsForSet()
                .members(materialKey(conversationId)))) {
            shownMaterialIds.add(Long.valueOf(value));
        }
        Long selectionTurnId = nullableLong(redisTemplate.opsForValue()
                .get(selectionActiveKey(conversationId)));
        Map<String, Long> selections = selectionTurnId == null
                ? Map.of() : longMap(redisTemplate.opsForHash()
                        .entries(selectionChoicesKey(
                                conversationId, selectionTurnId)));
        Map<String, TrpgSaveSnapshotDTO.LocationOptionSnapshot> options =
                selectionTurnId == null ? Map.of()
                        : locationOptions(redisTemplate.opsForHash()
                                .entries(selectionOptionsKey(
                                        conversationId, selectionTurnId)));
        List<TrpgSaveSnapshotDTO.SceneProgressSnapshot> progress =
                safeList(sceneIds).stream()
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .map(sceneId -> new TrpgSaveSnapshotDTO.SceneProgressSnapshot()
                                .setSceneId(sceneId)
                                .setReadyActors(safeSet(redisTemplate.opsForSet()
                                        .members(sceneReadyKey(
                                                conversationId, sceneId))))
                                .setFinishRequested(Boolean.TRUE.equals(
                                        redisTemplate.hasKey(sceneFinishKey(
                                                conversationId, sceneId)))))
                        .toList();
        return new TrpgSaveSnapshotDTO.RedisStateSnapshot()
                .setShownMaterialIds(Set.copyOf(shownMaterialIds))
                .setSceneSelectionTurnId(selectionTurnId)
                .setSceneSelections(Map.copyOf(selections))
                .setSceneOptions(Map.copyOf(options))
                .setSceneProgress(progress)
                .setRunFinishRequested(Boolean.TRUE.equals(
                        redisTemplate.hasKey(runFinishKey(conversationId))));
    }

    @Override
    public void restore(
            Long conversationId,
            TrpgSaveSnapshotDTO.RedisStateSnapshot snapshot) {
        clear(conversationId);
        if (snapshot == null) {
            return;
        }
        restoreMaterials(conversationId, snapshot.getShownMaterialIds());
        restoreSelection(conversationId, snapshot);
        restoreSceneProgress(conversationId, snapshot.getSceneProgress());
        if (Boolean.TRUE.equals(snapshot.getRunFinishRequested())) {
            redisTemplate.opsForValue().set(
                    runFinishKey(conversationId), "1", TRANSIENT_TTL);
        }
    }

    @Override
    public void clear(Long conversationId) {
        List<String> dynamicKeys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match("trpg:group:*:" + conversationId + ":*")
                .count(RedisConstant.REDIS_SCAN_COUNT)
                .build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                dynamicKeys.add(cursor.next());
            }
        }
        if (!dynamicKeys.isEmpty()) {
            redisTemplate.delete(dynamicKeys);
        }
        redisTemplate.delete(List.of(
                materialKey(conversationId),
                selectionActiveKey(conversationId),
                contextWindowKey(conversationId),
                runFinishKey(conversationId),
                RedisConstant.TRPG_TURN_DIRECTION_PREFIX
                        + conversationId));
    }

    private void restoreMaterials(Long conversationId, Set<Long> materialIds) {
        String key = materialKey(conversationId);
        for (Long materialId : safeSet(materialIds)) {
            if (materialId != null) {
                redisTemplate.opsForSet().add(key, materialId.toString());
            }
        }
        if (materialIds != null && !materialIds.isEmpty()) {
            redisTemplate.expire(key, MATERIAL_TTL);
        }
    }

    private void restoreSelection(
            Long conversationId,
            TrpgSaveSnapshotDTO.RedisStateSnapshot snapshot) {
        Long turnId = snapshot.getSceneSelectionTurnId();
        if (turnId == null) {
            return;
        }
        String choicesKey = selectionChoicesKey(conversationId, turnId);
        for (Map.Entry<String, Long> entry
                : safeMap(snapshot.getSceneSelections()).entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                redisTemplate.opsForHash().put(
                        choicesKey, entry.getKey(), entry.getValue().toString());
            }
        }
        if (snapshot.getSceneSelections() != null
                && !snapshot.getSceneSelections().isEmpty()) {
            redisTemplate.expire(choicesKey, TRANSIENT_TTL);
        }
        String optionsKey = selectionOptionsKey(conversationId, turnId);
        for (Map.Entry<String, TrpgSaveSnapshotDTO.LocationOptionSnapshot> entry
                : safeMap(snapshot.getSceneOptions()).entrySet()) {
            TrpgSaveSnapshotDTO.LocationOptionSnapshot option = entry.getValue();
            if (entry.getKey() != null && option != null
                    && option.getLocationId() != null && option.getName() != null) {
                redisTemplate.opsForHash().put(
                        optionsKey,
                        entry.getKey(),
                        option.getLocationId() + "\t" + option.getName());
            }
        }
        if (snapshot.getSceneOptions() != null
                && !snapshot.getSceneOptions().isEmpty()) {
            redisTemplate.expire(optionsKey, TRANSIENT_TTL);
        }
        redisTemplate.opsForValue().set(
                selectionActiveKey(conversationId),
                turnId.toString(), TRANSIENT_TTL);
    }

    private void restoreSceneProgress(
            Long conversationId,
            List<TrpgSaveSnapshotDTO.SceneProgressSnapshot> progressSnapshots) {
        for (TrpgSaveSnapshotDTO.SceneProgressSnapshot progress
                : safeList(progressSnapshots)) {
            if (progress == null || progress.getSceneId() == null) {
                continue;
            }
            String readyKey = sceneReadyKey(
                    conversationId, progress.getSceneId());
            for (String actor : safeSet(progress.getReadyActors())) {
                if (actor != null) {
                    redisTemplate.opsForSet().add(readyKey, actor);
                }
            }
            if (progress.getReadyActors() != null
                    && !progress.getReadyActors().isEmpty()) {
                redisTemplate.expire(readyKey, TRANSIENT_TTL);
            }
            if (Boolean.TRUE.equals(progress.getFinishRequested())) {
                redisTemplate.opsForValue().set(
                        sceneFinishKey(conversationId, progress.getSceneId()),
                        "1", TRANSIENT_TTL);
            }
        }
    }

    private Map<String, Long> longMap(Map<Object, Object> values) {
        Map<String, Long> result = new LinkedHashMap<>();
        safeMap(values).forEach((key, value) -> result.put(
                key.toString(), Long.valueOf(value.toString())));
        return result;
    }

    private Map<String, TrpgSaveSnapshotDTO.LocationOptionSnapshot>
    locationOptions(Map<Object, Object> values) {
        Map<String, TrpgSaveSnapshotDTO.LocationOptionSnapshot> result =
                new LinkedHashMap<>();
        safeMap(values).forEach((key, value) -> {
            String[] parts = value.toString().split("\t", 2);
            if (parts.length == 2) {
                result.put(key.toString(),
                        new TrpgSaveSnapshotDTO.LocationOptionSnapshot()
                                .setLocationId(Long.valueOf(parts[0]))
                                .setName(parts[1]));
            }
        });
        return result;
    }

    private Long nullableLong(String value) {
        return value == null ? null : Long.valueOf(value);
    }

    private String materialKey(Long conversationId) {
        return RedisConstant.TRPG_SHOWN_MATERIALS_PREFIX + conversationId;
    }

    private String selectionActiveKey(Long conversationId) {
        return RedisConstant.TRPG_SCENE_SELECTION_PREFIX
                + conversationId + ":active";
    }

    private String selectionChoicesKey(Long conversationId, Long turnId) {
        return RedisConstant.TRPG_SCENE_SELECTION_PREFIX
                + conversationId + ":" + turnId + ":choices";
    }

    private String selectionOptionsKey(Long conversationId, Long turnId) {
        return RedisConstant.TRPG_SCENE_SELECTION_PREFIX
                + conversationId + ":" + turnId + ":options";
    }

    private String sceneReadyKey(Long conversationId, Long sceneId) {
        return RedisConstant.TRPG_SCENE_PROGRESS_PREFIX
                + conversationId + ":" + sceneId + ":ready";
    }

    private String sceneFinishKey(Long conversationId, Long sceneId) {
        return RedisConstant.TRPG_SCENE_PROGRESS_PREFIX
                + conversationId + ":" + sceneId + ":finish";
    }

    private String contextWindowKey(Long conversationId) {
        return RedisConstant.TRPG_CONTEXT_WINDOW_PREFIX + conversationId;
    }

    private String runFinishKey(Long conversationId) {
        return RedisConstant.TRPG_RUN_FINISH_PREFIX + conversationId;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private <T> Set<T> safeSet(Set<T> values) {
        return values == null ? Set.of() : values;
    }

    private <K, V> Map<K, V> safeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }
}
