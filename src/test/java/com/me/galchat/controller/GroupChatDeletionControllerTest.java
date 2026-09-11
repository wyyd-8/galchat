package com.me.galchat.controller;

import com.me.galchat.service.impl.group.GroupChatService;
import com.me.galchat.service.impl.group.GroupChatWithdrawalService;
import com.me.galchat.service.impl.group.GroupConversationDeletionService;
import com.me.galchat.service.impl.group.GroupConversationLifecycleService;
import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.group.GroupGenerationStreamRegistry;
import com.me.galchat.service.impl.group.GroupReplyPlanService;
import com.me.galchat.service.impl.trpg.TrpgContextWindowService;
import com.me.galchat.service.impl.trpg.TrpgGameTimeService;
import com.me.galchat.service.impl.trpg.TrpgTurnExecutionService;
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
