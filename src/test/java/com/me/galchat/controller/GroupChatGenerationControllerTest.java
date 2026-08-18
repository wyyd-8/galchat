package com.me.galchat.controller;

import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.dto.GroupEndExplorationDTO;
import com.me.galchat.domain.dto.GroupSceneSelectionDTO;
import com.me.galchat.domain.dto.GroupTurnContinueDTO;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.service.impl.GroupChatService;
import com.me.galchat.service.impl.GroupChatWithdrawalService;
import com.me.galchat.service.impl.GroupConversationLifecycleService;
import com.me.galchat.service.impl.GroupConversationService;
import com.me.galchat.service.impl.GroupGenerationStreamRegistry;
import com.me.galchat.service.impl.GroupReplyPlanService;
import com.me.galchat.service.impl.TrpgContextWindowService;
import com.me.galchat.service.impl.TrpgGameTimeService;
import com.me.galchat.service.impl.TrpgTurnExecutionService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GroupChatGenerationControllerTest {

    @Test
    void resumesTheGenerationRegisteredByTheMessageEndpoint() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupChatService groupChatService = mock(GroupChatService.class);
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry();
        GroupChatController controller = new GroupChatController(
                conversationService,
                mock(GroupConversationLifecycleService.class),
                groupChatService,
                registry,
                mock(GroupChatWithdrawalService.class),
                mock(GroupReplyPlanService.class),
                mock(TrpgContextWindowService.class),
                mock(TrpgTurnExecutionService.class),
                mock(TrpgGameTimeService.class));
        GroupChatRequestDTO request = new GroupChatRequestDTO();
        request.setClientRequestId("generation-7");
        request.setContent("继续调查");
        GroupChatEvent delta = GroupChatEvent.builder()
                .eventType("message.delta")
                .conversationId(7L)
                .delta("脚步声")
                .build();
        when(groupChatService.chat(7L, request))
                .thenReturn(Flux.just(delta));

        controller.chat(7L, request).collectList().block();
        List<GroupChatEvent> resumed = controller.resumeGeneration(
                7L, "generation-7").collectList().block();

        assertThat(resumed).extracting(GroupChatEvent::getEventType)
                .containsExactly("message.delta", "stream.caught_up");
    }

    @Test
    void authenticatesBeforeStartingEveryGenerationEndpoint() {
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        GroupChatService groupChatService = mock(GroupChatService.class);
        TrpgTurnExecutionService turnExecutionService =
                mock(TrpgTurnExecutionService.class);
        GroupChatController controller = new GroupChatController(
                conversationService,
                mock(GroupConversationLifecycleService.class),
                groupChatService,
                new GroupGenerationStreamRegistry(),
                mock(GroupChatWithdrawalService.class),
                mock(GroupReplyPlanService.class),
                mock(TrpgContextWindowService.class),
                turnExecutionService,
                mock(TrpgGameTimeService.class));
        UserAuthException unauthorized =
                new UserAuthException("用户未登录");
        doThrow(unauthorized).when(conversationService)
                .requireAuthorized(7L);

        assertThatThrownBy(() -> controller.chat(
                7L, new GroupChatRequestDTO())).isSameAs(unauthorized);
        assertThatThrownBy(() -> controller.continueTurn(
                7L, new GroupTurnContinueDTO())).isSameAs(unauthorized);
        assertThatThrownBy(() -> controller.retryTurnStep(
                7L, 8L, 9L, new GroupTurnContinueDTO()))
                .isSameAs(unauthorized);
        assertThatThrownBy(() -> controller.submitTurnMessage(
                7L, 8L, 9L, new GroupChatRequestDTO()))
                .isSameAs(unauthorized);
        assertThatThrownBy(() -> controller.submitSceneSelection(
                7L, 8L, 9L, new GroupSceneSelectionDTO()))
                .isSameAs(unauthorized);
        assertThatThrownBy(() -> controller.endExploration(
                7L, 8L, 9L, new GroupEndExplorationDTO()))
                .isSameAs(unauthorized);

        verifyNoInteractions(groupChatService, turnExecutionService);
    }
}
