package com.me.galchat.groupchat.order;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.exception.UserRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupReplyOrderControllerTest {

    private final GroupReplyOrderController controller = new GroupReplyOrderController(
            new ExplicitReplyOrderPolicy(), new ListReplyOrderPolicy());

    @Test
    void explicitPlanPreservesRequestedOrder() {
        GroupChatRequestDTO request = new GroupChatRequestDTO();
        request.setReplyPlan(List.of(target(3L), target(1L), target(2L)));

        GroupReplyOrderController.PlannedReplies result = controller.plan(request, List.of());

        assertThat(result.policy()).isEqualTo("explicit");
        assertThat(result.items()).extracting(ReplyPlanItem::speakerId)
                .containsExactly(3L, 1L, 2L);
    }

    @Test
    void nullPlanUsesEnabledMemberListOrder() {
        GroupChatRequestDTO request = new GroupChatRequestDTO();
        List<GroupChatMember> members = List.of(member(9L, true), member(4L, false), member(7L, true));

        GroupReplyOrderController.PlannedReplies result = controller.plan(request, members);

        assertThat(result.policy()).isEqualTo("list");
        assertThat(result.items()).extracting(ReplyPlanItem::speakerId).containsExactly(9L, 7L);
    }

    @Test
    void duplicateExplicitSpeakerIsRejected() {
        GroupChatRequestDTO request = new GroupChatRequestDTO();
        request.setReplyPlan(List.of(target(1L), target(1L)));

        assertThatThrownBy(() -> controller.plan(request, List.of()))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("重复");
    }

    private GroupChatRequestDTO.ReplyTarget target(Long id) {
        GroupChatRequestDTO.ReplyTarget target = new GroupChatRequestDTO.ReplyTarget();
        target.setSpeakerId(id);
        return target;
    }

    private GroupChatMember member(Long id, boolean enabled) {
        return new GroupChatMember()
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(id)
                .setEnabled(enabled);
    }
}
