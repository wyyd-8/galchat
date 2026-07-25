package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatTurn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroupChatWithdrawalServiceTest {

    @Test
    void selectsLatestNonWithdrawnTurnAndCountsConsecutivePlaceholders() {
        GroupChatTurn candidate = new GroupChatTurn().setId(10L).setStatus(GroupChatConstant.STATUS_COMPLETED);

        GroupChatWithdrawalService.WithdrawCandidate result =
                GroupChatWithdrawalService.selectWithdrawCandidate(List.of(
                        new GroupChatTurn().setId(30L).setStatus(GroupChatConstant.STATUS_WITHDRAWN),
                        new GroupChatTurn().setId(20L).setStatus(GroupChatConstant.STATUS_WITHDRAWN),
                        candidate));

        assertThat(result.turn()).isSameAs(candidate);
        assertThat(result.consecutiveWithdrawCount()).isEqualTo(2);
    }

    @Test
    void noCandidateRemainsAfterThreeWithdrawnTurns() {
        GroupChatWithdrawalService.WithdrawCandidate result =
                GroupChatWithdrawalService.selectWithdrawCandidate(List.of(
                        new GroupChatTurn().setId(30L).setStatus(GroupChatConstant.STATUS_WITHDRAWN),
                        new GroupChatTurn().setId(20L).setStatus(GroupChatConstant.STATUS_WITHDRAWN),
                        new GroupChatTurn().setId(10L).setStatus(GroupChatConstant.STATUS_WITHDRAWN)));

        assertThat(result.turn()).isNull();
        assertThat(result.consecutiveWithdrawCount()).isEqualTo(3);
    }
}
