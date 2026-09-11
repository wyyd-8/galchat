package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.vo.TrpgParticipantRunVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.TrpgParticipantHistoryMapper;
import com.me.galchat.service.IUserWorldPrefixService;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrpgParticipantHistoryServiceTest {
    private final TrpgParticipantHistoryMapper mapper = mock(TrpgParticipantHistoryMapper.class);
    private final IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
    private final TrpgParticipantHistoryService service = new TrpgParticipantHistoryService(worlds, mapper);

    @Test
    void rejectsUnauthorizedWorldsBeforeReadingHistory() {
        doThrow(new UserRequestException("无权访问")).when(worlds).checkUserWorldAuth(2L,false);
        assertThatThrownBy(() -> service.summaries(2L)).isInstanceOf(UserRequestException.class);
        assertThatThrownBy(() -> service.runs(2L,11L,null,10)).isInstanceOf(UserRequestException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsCharactersOutsideWorldAndInvalidPagination() {
        assertThatThrownBy(() -> service.runs(1L,99L,null,10)).isInstanceOf(UserRequestException.class);
        when(mapper.characterExists(1L,11L)).thenReturn(true);
        assertThatThrownBy(() -> service.runs(1L,11L,"invalid",10)).isInstanceOf(UserRequestException.class);
        assertThatThrownBy(() -> service.runs(1L,11L,null,0)).isInstanceOf(UserRequestException.class);
        assertThatThrownBy(() -> service.runs(1L,11L,null,51)).isInstanceOf(UserRequestException.class);
    }

    @Test
    void exposesOnlyRequestedPageAndUsesLastVisibleRowAsCursor() {
        when(mapper.characterExists(1L,11L)).thenReturn(true);
        var at = LocalDateTime.of(2026,9,1,12,0);
        var a = new TrpgParticipantRunVO().setConversationId(102L).setCompletedAt(at);
        var b = new TrpgParticipantRunVO().setConversationId(101L).setCompletedAt(at);
        when(mapper.selectCompletedRuns(1L,11L,null,null,2)).thenReturn(List.of(a,b));
        var first=service.runs(1L,11L,null,1);
        assertThat(first.items()).containsExactly(a);
        assertThat(first.nextCursor()).isNotBlank();
        when(mapper.selectCompletedRuns(1L,11L,at,102L,2)).thenReturn(List.of(b));
        var next=service.runs(1L,11L,first.nextCursor(),1);
        assertThat(next.items()).containsExactly(b);
        assertThat(next.nextCursor()).isNull();
    }
}
