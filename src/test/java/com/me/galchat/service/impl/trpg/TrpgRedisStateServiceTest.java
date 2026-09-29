package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrpgRedisStateServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private Cursor<String> cursor;

    private TrpgRedisStateService service;

    @BeforeEach
    void setUp() {
        service = new TrpgRedisStateService(redisTemplate);
    }

    @Test
    void captureIncludesMaterialsSelectionProgressAndRunFinishRequest() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(setOperations.members(
                RedisConstant.TRPG_SHOWN_MATERIALS_PREFIX + "51"))
                .thenReturn(Set.of("9", "10"));
        when(valueOperations.get(
                RedisConstant.TRPG_SCENE_SELECTION_PREFIX + "51:active"))
                .thenReturn("77");
        when(hashOperations.entries(
                RedisConstant.TRPG_SCENE_SELECTION_PREFIX + "51:77:choices"))
                .thenReturn(Map.of("user:401", "301"));
        when(hashOperations.entries(
                RedisConstant.TRPG_SCENE_SELECTION_PREFIX + "51:77:options"))
                .thenReturn(Map.of("1", "301\t码头"));
        when(setOperations.members(
                RedisConstant.TRPG_SCENE_PROGRESS_PREFIX + "51:301:ready"))
                .thenReturn(Set.of("user:401", "character:2"));
        when(redisTemplate.hasKey(
                RedisConstant.TRPG_SCENE_PROGRESS_PREFIX + "51:301:finish"))
                .thenReturn(true);

        TrpgSaveSnapshotDTO.RedisStateSnapshot snapshot =
                service.capture(51L, List.of(301L));

        assertThat(snapshot.getShownMaterialIds()).containsExactlyInAnyOrder(9L, 10L);
        assertThat(snapshot.getSceneSelectionTurnId()).isEqualTo(77L);
        assertThat(snapshot.getSceneSelections()).containsEntry("user:401", 301L);
        assertThat(snapshot.getSceneOptions().get("1").getLocationId()).isEqualTo(301L);
        assertThat(snapshot.getSceneOptions().get("1").getName()).isEqualTo("码头");
        assertThat(snapshot.getSceneProgress()).singleElement().satisfies(progress -> {
            assertThat(progress.getSceneId()).isEqualTo(301L);
            assertThat(progress.getReadyActors())
                    .containsExactlyInAnyOrder("user:401", "character:2");
            assertThat(progress.getFinishRequested()).isTrue();
        });
    }

    @Test
    void restoreDeletesEveryRunScopedTransientKeyBeforeRebuildingState() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.scan(any())).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(
                RedisConstant.TRPG_SCENE_SELECTION_PREFIX + "51:88:choices",
                RedisConstant.TRPG_SCENE_PROGRESS_PREFIX + "51:999:ready");
        TrpgSaveSnapshotDTO.RedisStateSnapshot snapshot =
                new TrpgSaveSnapshotDTO.RedisStateSnapshot()
                        .setShownMaterialIds(Set.of(9L))
                        .setSceneSelectionTurnId(77L)
                        .setSceneSelections(Map.of("user:401", 301L))
                        .setSceneOptions(Map.of("1",
                                new TrpgSaveSnapshotDTO.LocationOptionSnapshot()
                                        .setLocationId(301L)
                                        .setName("码头")))
                        .setSceneProgress(List.of(
                                new TrpgSaveSnapshotDTO.SceneProgressSnapshot()
                                        .setSceneId(301L)
                                        .setReadyActors(Set.of("user:401"))
                                        .setFinishRequested(true)));

        service.restore(51L, snapshot);

        verify(redisTemplate).delete(List.of(
                RedisConstant.TRPG_SCENE_SELECTION_PREFIX + "51:88:choices",
                RedisConstant.TRPG_SCENE_PROGRESS_PREFIX + "51:999:ready"));
        verify(setOperations).add(
                RedisConstant.TRPG_SHOWN_MATERIALS_PREFIX + "51", "9");
        verify(hashOperations).put(
                RedisConstant.TRPG_SCENE_SELECTION_PREFIX + "51:77:choices",
                "user:401", "301");
        verify(hashOperations).put(
                RedisConstant.TRPG_SCENE_SELECTION_PREFIX + "51:77:options",
                "1", "301\t码头");
        verify(setOperations).add(
                RedisConstant.TRPG_SCENE_PROGRESS_PREFIX + "51:301:ready",
                "user:401");
        verify(valueOperations).set(
                RedisConstant.TRPG_SCENE_PROGRESS_PREFIX + "51:301:finish",
                "1");
        verify(valueOperations).set(
                RedisConstant.TRPG_SCENE_SELECTION_PREFIX + "51:active", "77");
        verify(redisTemplate, org.mockito.Mockito.never()).expire(
                org.mockito.ArgumentMatchers.startsWith(RedisConstant.TRPG_SCENE_SELECTION_PREFIX),
                any(java.time.Duration.class));
        verify(redisTemplate, org.mockito.Mockito.never()).expire(
                org.mockito.ArgumentMatchers.startsWith(RedisConstant.TRPG_SCENE_PROGRESS_PREFIX),
                any(java.time.Duration.class));
    }

    @Test
    void clearDeletesEveryRunScopedTransientKeyWithoutRestoringAnything() {
        when(redisTemplate.scan(any())).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(
                RedisConstant.TRPG_PROPOSAL_ORDER_PREFIX + "51:88",
                RedisConstant.TRPG_SCENE_PROGRESS_PREFIX + "51:999:ready");

        service.clear(51L);

        verify(redisTemplate).delete(List.of(
                RedisConstant.TRPG_PROPOSAL_ORDER_PREFIX + "51:88",
                RedisConstant.TRPG_SCENE_PROGRESS_PREFIX + "51:999:ready"));
        verify(redisTemplate).delete(List.of(
                RedisConstant.TRPG_SHOWN_MATERIALS_PREFIX + "51",
                RedisConstant.TRPG_SCENE_SELECTION_PREFIX + "51:active",
                RedisConstant.TRPG_CONTEXT_WINDOW_PREFIX + "51",
                RedisConstant.TRPG_TURN_DIRECTION_PREFIX + "51"));
    }

    @Test
    void endingOneRunDoesNotDeleteAnotherRunsSceneWithTheSameNumericId() {
        var keys = new java.util.LinkedHashSet<>(List.of(
                "trpg:group:scene-progress:7:20:finish",
                "trpg:group:scene-selection:7:9:choices",
                "trpg:group:proposal-order:7:state",
                "trpg:group:scene-progress:17:7:finish",
                "trpg:group:scene-selection:17:7:options"));
        when(redisTemplate.scan(any())).thenAnswer(call -> {
            String pattern = call.<org.springframework.data.redis.core.ScanOptions>getArgument(0).getPattern();
            var matching = keys.stream().filter(key ->
                    org.springframework.util.PatternMatchUtils.simpleMatch(pattern, key)).toList().iterator();
            Cursor<String> scan = org.mockito.Mockito.mock(Cursor.class);
            when(scan.hasNext()).thenAnswer(ignored -> matching.hasNext());
            when(scan.next()).thenAnswer(ignored -> matching.next());
            return scan;
        });
        when(redisTemplate.delete(any(java.util.Collection.class))).thenAnswer(call -> {
            keys.removeAll(call.getArgument(0));
            return 1L;
        });

        service.clear(7L);

        assertThat(keys).containsExactlyInAnyOrder(
                "trpg:group:scene-progress:17:7:finish", "trpg:group:scene-selection:17:7:options");
    }
}
