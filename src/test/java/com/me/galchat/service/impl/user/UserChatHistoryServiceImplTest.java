package com.me.galchat.service.impl.user;

import com.me.galchat.constant.ChatConstant;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatThinkingHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatThinkingHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import com.me.galchat.service.IUserWorldPrefixService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserChatHistoryServiceImplTest {

    private UserChatHistoryServiceImpl careService(IUserWorldPrefixService worlds, UserChatHistoryMapper mapper) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserChatHistory.class);
        var service = new UserChatHistoryServiceImpl(worlds, null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }

    @Test
    void careFeedAuthorizesWorldBeforeReadingAnyMessages() {
        var worlds = mock(IUserWorldPrefixService.class);
        var mapper = mock(UserChatHistoryMapper.class);
        when(worlds.checkUserWorldAuth(3L, true)).thenThrow(new com.me.galchat.exception.UserAuthException("forbidden"));
        var service = careService(worlds, mapper);

        assertThatThrownBy(() -> service.listCareMessages(3L, 0L))
                .isInstanceOf(com.me.galchat.exception.UserAuthException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void firstCareCheckCreatesBaselineWithoutReplayingOldNotifications() {
        var worlds = mock(IUserWorldPrefixService.class);
        var mapper = mock(UserChatHistoryMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(new UserChatHistory().setId(99L)));

        var page = careService(worlds, mapper).listCareMessages(3L, null);

        assertThat(page.messages()).isEmpty();
        assertThat(page.nextCursor()).isEqualTo(99);
        assertThat(page.hasMore()).isFalse();
        verify(worlds).checkUserWorldAuth(3L, true);
    }

    @Test
    void careFeedPagesOnlyStandaloneAssistantMessagesInTheRequestedWorld() {
        var mapper = mock(UserChatHistoryMapper.class);
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserChatHistory>) invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("user_world_id =", "type =", "user_message_id IS NULL", "id >", "ORDER BY id ASC", "limit 101");
            assertThat(query.getParamNameValuePairs().values()).contains(3L, "assistant", 50L);
            return java.util.stream.LongStream.rangeClosed(51, 151)
                    .mapToObj(id -> new UserChatHistory().setId(id)).toList();
        });

        var page = careService(mock(IUserWorldPrefixService.class), mapper).listCareMessages(3L, 50L);

        assertThat(page.messages()).hasSize(100);
        assertThat(page.messages().getFirst().getId()).isEqualTo(51);
        assertThat(page.nextCursor()).isEqualTo(150);
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void emptyCarePageKeepsCursorAndRejectsNegativeCursors() {
        var mapper = mock(UserChatHistoryMapper.class);
        var service = careService(mock(IUserWorldPrefixService.class), mapper);
        when(mapper.selectList(any())).thenReturn(List.of());

        var page = service.listCareMessages(3L, 50L);

        assertThat(page.messages()).isEmpty();
        assertThat(page.nextCursor()).isEqualTo(50);
        assertThat(page.hasMore()).isFalse();
        assertThatThrownBy(() -> service.listCareMessages(3L, -1L))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class);
    }

    @Test
    void historyIncludesReasoningToolsRepliesAndProactiveMessagesWithoutAModeSetting() {
        for (Class<?> entity : List.of(UserChatHistory.class, UserChatThinkingHistory.class, UserChatToolCall.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
        var worlds = mock(IUserWorldPrefixService.class);
        when(worlds.checkUserWorldAuth(3L, true)).thenReturn(new UserWorldPrefix().setId(3L));
        var histories = mock(UserChatHistoryMapper.class);
        var thoughts = mock(UserChatThinkingHistoryMapper.class);
        var tools = mock(UserChatToolCallMapper.class);
        var user = new UserChatHistory().setId(10L).setType("user").setContent("问题");
        var assistant = new UserChatHistory().setId(11L).setUserMessageId(10L)
                .setStepNo(1).setType("assistant").setContent("回答");
        var care = new UserChatHistory().setId(20L).setType("assistant").setContent("关怀消息");
        when(histories.selectList(any())).thenReturn(new ArrayList<>(List.of(care, user)), List.of(assistant));
        when(thoughts.selectList(any())).thenReturn(List.of(new UserChatThinkingHistory()
                .setId(1L).setUserMessageId(10L).setStepNo(1).setReasoningContent("思考")));
        when(tools.selectList(any())).thenReturn(List.of(new UserChatToolCall()
                .setId(1L).setUserMessageId(10L).setStepNo(1)));
        var service = new UserChatHistoryServiceImpl(worlds, thoughts, tools, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "baseMapper", histories);
        ReflectionTestUtils.setField(service, "entityClass", UserChatHistory.class);

        var messages = service.listHistory(3L, 7L, null, 30);

        assertThat(messages).extracting(UserChatHistory::getType)
                .containsExactly("user", "thinking", "tool", "assistant", "assistant");
        assertThat(messages).extracting(UserChatHistory::getContent)
                .containsExactly("问题", "思考", null, "回答", "关怀消息");
        verify(worlds).checkUserWorldAuth(3L, true);
    }

    @Test
    void newestProactiveAssistantIsSelectedBeforeAnOlderUserRound() {
        UserChatHistory proactive = new UserChatHistory()
                .setId(20L)
                .setType(MessageType.ASSISTANT.getValue());
        UserChatHistory user = new UserChatHistory()
                .setId(10L)
                .setType(MessageType.USER.getValue());

        UserChatHistoryServiceImpl.WithdrawCandidate candidate =
                UserChatHistoryServiceImpl.selectWithdrawCandidate(List.of(proactive, user));

        assertThat(candidate.anchor().getId()).isEqualTo(20L);
        assertThat(candidate.consecutiveWithdrawCount()).isZero();
    }

    @Test
    void withdrawnPlaceholdersCountAcrossDifferentLogicalRoundKinds() {
        UserChatHistory user = new UserChatHistory()
                .setId(10L)
                .setType(MessageType.USER.getValue());
        UserChatHistoryServiceImpl.WithdrawCandidate candidate =
                UserChatHistoryServiceImpl.selectWithdrawCandidate(List.of(
                        new UserChatHistory().setId(30L).setType(ChatConstant.WITHDRAWN_TYPE),
                        new UserChatHistory().setId(20L).setType(ChatConstant.WITHDRAWN_TYPE),
                        user));

        assertThat(candidate.anchor()).isSameAs(user);
        assertThat(candidate.consecutiveWithdrawCount()).isEqualTo(2);
    }
}
