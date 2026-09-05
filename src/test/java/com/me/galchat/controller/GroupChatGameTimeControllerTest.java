package com.me.galchat.controller;

import com.me.galchat.constant.TrpgGameTimePeriod;
import com.me.galchat.domain.dto.TrpgGameTimeUpdateDTO;
import com.me.galchat.domain.vo.TrpgGameTimeVO;
import com.me.galchat.service.impl.group.GroupChatService;
import com.me.galchat.service.impl.group.GroupGenerationStreamRegistry;
import com.me.galchat.service.impl.group.GroupChatWithdrawalService;
import com.me.galchat.service.impl.group.GroupConversationLifecycleService;
import com.me.galchat.service.impl.group.GroupConversationDeletionService;
import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.group.GroupReplyPlanService;
import com.me.galchat.service.impl.trpg.TrpgContextWindowService;
import com.me.galchat.service.impl.trpg.TrpgGameTimeService;
import com.me.galchat.service.impl.trpg.TrpgTurnExecutionService;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupChatGameTimeControllerTest {

    @AfterEach
    void clearCurrentUser() {
        CurrentHolder.remove();
    }

    @Test
    void correctionPassesCurrentUserToGameTimeService() {
        TrpgGameTimeService gameTimeService =
                mock(TrpgGameTimeService.class);
        GroupChatController controller = new GroupChatController(
                mock(GroupConversationService.class),
                mock(GroupConversationLifecycleService.class),
                mock(GroupChatService.class),
                mock(GroupGenerationStreamRegistry.class),
                mock(GroupChatWithdrawalService.class),
                mock(GroupReplyPlanService.class),
                mock(TrpgContextWindowService.class),
                mock(TrpgTurnExecutionService.class),
                gameTimeService,
                mock(GroupConversationDeletionService.class));
        CurrentHolder.setCurrentId(12);
        TrpgGameTimeUpdateDTO request =
                new TrpgGameTimeUpdateDTO(
                        2, TrpgGameTimePeriod.MORNING.name(), 4);
        TrpgGameTimeVO updated = new TrpgGameTimeVO(
                2, "MORNING", "上午", "第二天 - 上午",
                5, LocalDateTime.of(2026, 8, 7, 10, 0));
        when(gameTimeService.correct(12L, 7L, request))
                .thenReturn(updated);

        var result = controller.updateGameTime(7L, request);

        assertThat(result.getData()).isSameAs(updated);
        verify(gameTimeService).correct(12L, 7L, request);
    }
}
