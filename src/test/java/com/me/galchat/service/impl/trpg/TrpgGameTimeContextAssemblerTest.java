package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.mapper.GroupConversationMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgGameTimeContextAssemblerTest {

    @Test
    void formatsLatestInitializedTimeForModelContext() {
        GroupConversationMapper mapper =
                mock(GroupConversationMapper.class);
        when(mapper.selectById(7L)).thenReturn(
                new GroupConversation()
                        .setId(7L)
                        .setGameDayNo(2)
                        .setGameTimePeriod("EVENING")
                        .setGameTimeRevision(3));

        String context = new TrpgGameTimeContextAssembler(mapper)
                .format(7L);

        assertThat(context).isEqualTo(
                "<current-game-time day=\"2\" period=\"EVENING\">"
                        + "第二天 - 晚上</current-game-time>");
    }

    @Test
    void marksTimeAsUninitializedWhenRunHasNoTime() {
        GroupConversationMapper mapper =
                mock(GroupConversationMapper.class);
        when(mapper.selectById(7L)).thenReturn(
                new GroupConversation().setId(7L));

        assertThat(new TrpgGameTimeContextAssembler(mapper).format(7L))
                .isEqualTo("<current-game-time initialized=\"false\">"
                        + "尚未设定</current-game-time>");
    }
}
