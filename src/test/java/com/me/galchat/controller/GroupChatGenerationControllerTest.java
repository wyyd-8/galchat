package com.me.galchat.controller;

import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.vo.GroupChatEvent;
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
import static org.mockito.Mockito.mock;
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
}
