package com.me.galchat.vector;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.VectorConstant;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatTopic;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.mapper.GroupChatMemberMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GroupTopicVectorServiceTest {

    @Test
    void archivedTopicStoresEnabledCharacterVisibility() {
        GroupChatMessageMapper messageMapper = mock(GroupChatMessageMapper.class);
        GroupChatMemberMapper memberMapper = mock(GroupChatMemberMapper.class);
        ChatClient rewriteClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        VectorStore vectorStore = mock(VectorStore.class);
        GroupTopicVectorService service = new GroupTopicVectorService(
                messageMapper,
                memberMapper,
                rewriteClient,
                vectorStore,
                mock(DocumentRetriever.class),
                mock(EmbeddingModel.class),
                mock(JdbcTemplate.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setUserWorldId(3L);
        GroupChatTopic topic = new GroupChatTopic()
                .setId(5L)
                .setConversationId(7L)
                .setStartSequence(1L);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                new GroupChatMessage()
                        .setConversationId(7L)
                        .setSequenceNo(1L)
                        .setSpeakerType(GroupChatConstant.ACTOR_USER)
                        .setContent("仓库里发现了一把钥匙")
                        .setStatus(GroupChatConstant.STATUS_COMPLETED)
                        .setVisibility("public")));
        when(memberMapper.selectList(any())).thenReturn(List.of(
                member(11L, 1),
                member(12L, 2)));
        when(rewriteClient.prompt().system(any(String.class)).user(any(String.class))
                .call().content()).thenReturn("Alice 和 Bob 在仓库发现了一把钥匙。");

        service.addTopic(conversation, topic, 10L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documents.capture());
        assertThat(documents.getValue()).hasSize(1);
        assertThat(documents.getValue().getFirst().getMetadata())
                .containsEntry(VectorConstant.USER_WORLD_ID_METADATA_KEY, 3L)
                .containsEntry(VectorConstant.VISIBLE_CHARACTERS_METADATA_KEY, List.of(11L, 12L));
    }

    @Test
    void groupTopicQueryFiltersByWorldAndVisibleCharacter() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        GroupChatMemberMapper memberMapper = mock(GroupChatMemberMapper.class);
        GroupTopicVectorService service = new GroupTopicVectorService(
                mock(GroupChatMessageMapper.class),
                memberMapper,
                mock(ChatClient.class),
                mock(VectorStore.class),
                mock(DocumentRetriever.class),
                embeddingModel,
                jdbcTemplate);
        when(embeddingModel.embed("仓库钥匙")).thenReturn(new float[]{0.1F, 0.2F});
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        service.queryGroupTopics(3L, 11L, "仓库钥匙");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), parameters.capture());
        assertThat(sql.getValue())
                .contains("metadata::jsonb @> ?::jsonb")
                .contains("metadata::jsonb -> 'visibleCharacters' @> ?::jsonb");
        assertThat(Arrays.asList(parameters.getValue()))
                .contains("{\"userWorldId\":3}", "[11]");
        verifyNoInteractions(memberMapper);
    }

    private GroupChatMember member(Long characterId, int position) {
        return new GroupChatMember()
                .setConversationId(7L)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(characterId)
                .setEnabled(true)
                .setPosition(position);
    }
}
