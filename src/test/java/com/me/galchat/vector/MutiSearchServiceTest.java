package com.me.galchat.vector;

import com.me.galchat.constant.VectorConstant;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.service.DocumentReranker;
import com.me.galchat.service.IUserWorldPrefixService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MutiSearchServiceTest {

    @Test
    void searchBeforeChatLimitsDocumentsBySourceAndSkipsRerank() {
        ChatHistoryVectorService chatHistoryVectorService = mock(ChatHistoryVectorService.class);
        WorldDetailVectorService worldDetailVectorService = mock(WorldDetailVectorService.class);
        GroupTopicVectorService groupTopicVectorService = mock(GroupTopicVectorService.class);
        RecentChatMemoryService recentChatMemoryService = mock(RecentChatMemoryService.class);
        IUserWorldPrefixService userWorldPrefixService = mock(IUserWorldPrefixService.class);
        DocumentReranker documentReranker = mock(DocumentReranker.class);
        MutiSearchService mutiSearchService = new MutiSearchService(chatHistoryVectorService,
                worldDetailVectorService, groupTopicVectorService,
                recentChatMemoryService, userWorldPrefixService, documentReranker);

        when(userWorldPrefixService.getById(1L)).thenReturn(new UserWorldPrefix().setWorldId(99L));
        when(worldDetailVectorService.queryWorldDetail(99L, "query")).thenReturn(List.of(
                document("world detail 1"),
                document("world detail 2"),
                document("world detail 3")));
        when(chatHistoryVectorService.queryChatHistory(1L, 2L, "query")).thenReturn(List.of(
                document("chat history 1"),
                document("chat history 2")));
        String result = mutiSearchService.searchBeforeChat(1L, 2L, "query");

        assertThat(result).contains("来源: " + VectorConstant.WORLD_DETAIL_SOURCE);
        assertThat(result).contains("来源: " + VectorConstant.CHAT_HISTORY_SOURCE);
        assertThat(result).contains("world detail 1", "world detail 2", "chat history 1");
        assertThat(result).doesNotContain(
                "world detail 3", "chat history 2",
                "world event 1", "world event 2");
        verifyNoInteractions(documentReranker);
    }

    @Test
    void searchInfoIncludesSingleAndGroupChatMemoriesForTheSameCharacter() {
        ChatHistoryVectorService chatHistoryVectorService = mock(ChatHistoryVectorService.class);
        WorldDetailVectorService worldDetailVectorService = mock(WorldDetailVectorService.class);
        GroupTopicVectorService groupTopicVectorService = mock(GroupTopicVectorService.class);
        RecentChatMemoryService recentChatMemoryService = mock(RecentChatMemoryService.class);
        IUserWorldPrefixService userWorldPrefixService = mock(IUserWorldPrefixService.class);
        DocumentReranker documentReranker = mock(DocumentReranker.class);
        MutiSearchService mutiSearchService = new MutiSearchService(chatHistoryVectorService,
                worldDetailVectorService, groupTopicVectorService,
                recentChatMemoryService, userWorldPrefixService, documentReranker);

        when(userWorldPrefixService.getById(1L)).thenReturn(new UserWorldPrefix().setWorldId(99L));
        when(worldDetailVectorService.queryWorldDetail(99L, "query")).thenReturn(List.of());
        when(chatHistoryVectorService.queryChatHistory(1L, 2L, "query"))
                .thenReturn(List.of(document("single memory")));
        when(groupTopicVectorService.queryGroupTopics(1L, 2L, "query"))
                .thenReturn(List.of(document("group memory")));
        when(recentChatMemoryService.queryRecentMemories(1L, 2L)).thenReturn(List.of(
                sourcedDocument("recent single memory", VectorConstant.CHAT_HISTORY_SOURCE),
                sourcedDocument("recent group memory", VectorConstant.GROUP_TOPIC_SOURCE)));
        when(documentReranker.rerank(eq("query"), anyList(), eq(VectorConstant.RERANK_TOP_N)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        String result = mutiSearchService.searchInfo(1L, 2L, "query");

        assertThat(result)
                .contains("来源: " + VectorConstant.CHAT_HISTORY_SOURCE)
                .contains("来源: " + VectorConstant.GROUP_TOPIC_SOURCE)
                .contains("single memory")
                .contains("group memory")
                .contains("recent single memory")
                .contains("recent group memory");
        verify(groupTopicVectorService).queryGroupTopics(1L, 2L, "query");
    }

    private Document document(String text) {
        return Document.builder()
                .text(text)
                .build();
    }

    private Document sourcedDocument(String text, String source) {
        return Document.builder()
                .text(text)
                .metadata(VectorConstant.SOURCE_METADATA_KEY, source)
                .build();
    }
}
