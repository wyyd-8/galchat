package com.me.galchat.vector;

import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class TrpgTurnVectorServiceTest {

    @Test
    void searchRecallsOnlyTenVectorCandidatesBeforeRanking() {
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TrpgTurnVectorService service = new TrpgTurnVectorService(
                turnMapper, mock(GroupChatMessageMapper.class),
                mock(VectorStore.class), embeddingModel, jdbcTemplate);
        when(turnMapper.selectList(any())).thenReturn(List.of());
        when(embeddingModel.embed("仓库钥匙"))
                .thenReturn(new float[]{0.1F, 0.2F});
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        service.search(51L, "仓库钥匙");

        var parameters = org.mockito.ArgumentCaptor
                .forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                parameters.capture());
        assertThat(Arrays.asList(parameters.getValue()).getLast())
                .isEqualTo(10);
    }

    @Test
    void finalRankingAddsOnlyASmallRecencyWeightAfterVectorRecall() {
        LocalDateTime old = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime recent = LocalDateTime.of(2026, 9, 1, 0, 0);
        var olderRelevant = new TrpgTurnVectorService.Candidate(
                11L, old, 0.90);
        var newerRelevant = new TrpgTurnVectorService.Candidate(
                12L, recent, 0.89);
        var weakRecent = new TrpgTurnVectorService.Candidate(
                13L, recent, 0.40);

        List<TrpgTurnVectorService.Candidate> ranked =
                TrpgTurnVectorService.rank(List.of(
                        olderRelevant, newerRelevant, weakRecent), 3);

        assertThat(ranked).extracting(TrpgTurnVectorService.Candidate::turnId)
                .containsExactly(12L, 11L, 13L);
        assertThat(ranked.getFirst().score()).isEqualTo(0.901);
    }

    @Test
    void indexesOnlyCompletedPublicMessagesFromTheCurrentTurn() {
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        TrpgTurnVectorService service = new TrpgTurnVectorService(
                turnMapper, messageMapper, mock(VectorStore.class),
                mock(EmbeddingModel.class), mock(JdbcTemplate.class));

        assertThat(service.indexableText(new GroupChatTurn().setId(11L), List.of(
                new GroupChatMessage().setTurnId(11L).setVisibility("public")
                        .setStatus("completed").setSpeakerType("kp")
                        .setMessageKind("dialogue")
                        .setContent("门后传来脚步声"),
                new GroupChatMessage().setTurnId(11L).setVisibility("private")
                        .setStatus("completed").setMessageKind("dialogue")
                        .setContent("KP秘密"),
                new GroupChatMessage().setTurnId(12L).setVisibility("public")
                        .setStatus("completed").setMessageKind("dialogue")
                        .setContent("另一轮"))))
                .contains("门后传来脚步声")
                .doesNotContain("KP秘密", "另一轮");
    }
}
