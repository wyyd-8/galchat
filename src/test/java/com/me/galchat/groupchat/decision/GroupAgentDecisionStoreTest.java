package com.me.galchat.groupchat.decision;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatAgentDecision;
import com.me.galchat.mapper.GroupChatAgentDecisionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupAgentDecisionStoreTest {

    @Test
    void savesOneCompleteDecisionForTheReplyStep() {
        GroupChatAgentDecisionMapper mapper =
                mock(GroupChatAgentDecisionMapper.class);
        GroupAgentDecisionStore store =
                new GroupAgentDecisionStore(mapper);

        store.save(41L, "先确认出口，再靠近书桌。");

        ArgumentCaptor<GroupChatAgentDecision> captor =
                ArgumentCaptor.forClass(
                        GroupChatAgentDecision.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getReplyStepId())
                .isEqualTo(41L);
        assertThat(captor.getValue().getContent())
                .isEqualTo("先确认出口，再靠近书桌。");
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void batchesDecisionsByReplyStepForHistory() {
        GroupChatAgentDecisionMapper mapper =
                mock(GroupChatAgentDecisionMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                decision(41L, "判断一"),
                decision(43L, "判断二")));
        GroupAgentDecisionStore store =
                new GroupAgentDecisionStore(mapper);

        Map<Long, String> result =
                store.contentByReplyStepIds(
                        List.of(41L, 42L, 43L));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(
                Map.of(41L, "判断一", 43L, "判断二"));
    }

    @Test
    void returnsOnlyMapperFilteredCompletedPrivateHistory() {
        GroupChatAgentDecisionMapper mapper =
                mock(GroupChatAgentDecisionMapper.class);
        when(mapper.selectCompletedForActorContext(
                7L,
                GroupChatConstant.ACTION_TRPG_SCENE,
                9L,
                "scene:21")).thenReturn(List.of(
                decision(41L, "先前判断一"),
                decision(52L, "先前判断二")));
        GroupAgentDecisionStore store =
                new GroupAgentDecisionStore(mapper);

        List<String> result = store.completedContentForActorContext(
                7L,
                GroupChatConstant.ACTION_TRPG_SCENE,
                9L,
                "scene:21");

        assertThat(result).containsExactly(
                "先前判断一", "先前判断二");
    }

    @Test
    void deletesPreviousDecisionBeforeRetry() {
        GroupChatAgentDecisionMapper mapper =
                mock(GroupChatAgentDecisionMapper.class);
        GroupAgentDecisionStore store =
                new GroupAgentDecisionStore(mapper);

        store.deleteByReplyStepId(41L);

        verify(mapper).delete(any());
    }

    private GroupChatAgentDecision decision(
            Long replyStepId, String content) {
        return new GroupChatAgentDecision()
                .setReplyStepId(replyStepId)
                .setContent(content);
    }
}
