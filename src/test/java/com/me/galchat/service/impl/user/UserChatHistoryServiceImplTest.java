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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserChatHistoryServiceImplTest {

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
        var service = new UserChatHistoryServiceImpl(worlds, thoughts, tools, null, null, null, null, null, null);
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
