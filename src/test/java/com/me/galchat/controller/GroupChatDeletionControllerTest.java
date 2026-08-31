package com.me.galchat.controller;

import com.me.galchat.service.impl.GroupChatService;
import com.me.galchat.service.impl.GroupChatWithdrawalService;
import com.me.galchat.service.impl.GroupConversationDeletionService;
import com.me.galchat.service.impl.GroupConversationLifecycleService;
import com.me.galchat.service.impl.GroupConversationService;
import com.me.galchat.service.impl.GroupGenerationStreamRegistry;
import com.me.galchat.service.impl.GroupReplyPlanService;
import com.me.galchat.service.impl.TrpgContextWindowService;
import com.me.galchat.service.impl.TrpgGameTimeService;
import com.me.galchat.service.impl.TrpgTurnExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GroupChatDeletionControllerTest {

    @Test
    void exposesPermanentConversationDeletionEndpoint() throws Exception {
        GroupConversationDeletionService deletionService =
                mock(GroupConversationDeletionService.class);
        GroupChatController controller = new GroupChatController(
                mock(GroupConversationService.class),
                mock(GroupConversationLifecycleService.class),
                mock(GroupChatService.class),
                mock(GroupGenerationStreamRegistry.class),
                mock(GroupChatWithdrawalService.class),
                mock(GroupReplyPlanService.class),
                mock(TrpgContextWindowService.class),
                mock(TrpgTurnExecutionService.class),
                mock(TrpgGameTimeService.class),
                deletionService);

        var result = controller.deleteConversation(7L);

        assertThat(result.getCode()).isEqualTo(1);
        assertThat(result.getData()).isNull();
        verify(deletionService).delete(7L);
        DeleteMapping mapping = GroupChatController.class
                .getMethod("deleteConversation", Long.class)
                .getAnnotation(DeleteMapping.class);
        assertThat(mapping.value())
                .containsExactly("/conversations/{conversationId}");
    }
}
