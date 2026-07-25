package com.me.galchat.service.impl;

import com.me.galchat.constant.ChatConstant;
import com.me.galchat.domain.po.UserChatHistory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserChatHistoryServiceImplTest {

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
