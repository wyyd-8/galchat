package com.me.galchat.controller;

import com.me.galchat.domain.vo.TrpgCombatParticipantOverviewVO;
import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.trpg.TrpgCombatOverviewService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgCombatOverviewControllerTest {

    @Test
    void authorizesTheConversationBeforeReturningItsCombatOverview() {
        GroupConversationService conversations =
                mock(GroupConversationService.class);
        TrpgCombatOverviewService overviewService =
                mock(TrpgCombatOverviewService.class);
        TrpgCombatOverviewController controller =
                new TrpgCombatOverviewController(
                        conversations, overviewService);
        List<TrpgCombatParticipantOverviewVO> overview = List.of(
                new TrpgCombatParticipantOverviewVO(
                        501L, "林恩", true, List.of(),
                        7, 12, 2, 65, 0, 8, "0"));
        when(overviewService.list(7L)).thenReturn(overview);

        var result = controller.list(7L);

        assertThat(result.getData()).isSameAs(overview);
        InOrder order = inOrder(conversations, overviewService);
        order.verify(conversations).requireAuthorized(7L);
        order.verify(overviewService).list(7L);
    }
}
