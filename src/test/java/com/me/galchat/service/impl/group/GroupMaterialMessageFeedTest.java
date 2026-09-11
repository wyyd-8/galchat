package com.me.galchat.service.impl.group;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.mapper.GroupChatMessageMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupMaterialMessageFeedTest {

    @Test
    void feedReturnsOnlyNewCompletedMaterialMessagesForStep() {
        GroupChatMessageMapper mapper =
                mock(GroupChatMessageMapper.class);
        GroupMaterialMessageFeed feed =
                new GroupMaterialMessageFeed(mapper);
        when(mapper.selectList(any())).thenReturn(List.of(
                new GroupChatMessage().setId(61L)
                        .setReplyStepId(41L)
                        .setMessageKind(GroupChatConstant.MESSAGE_MATERIAL)
                        .setStatus(GroupChatConstant.STATUS_COMPLETED),
                new GroupChatMessage().setId(62L)
                        .setReplyStepId(41L)
                        .setMessageKind(GroupChatConstant.MESSAGE_MATERIAL)
                        .setStatus(GroupChatConstant.STATUS_COMPLETED)));

        assertThat(feed.listNew(41L, 61L))
                .extracting(GroupChatMessage::getId)
                .containsExactly(62L);
    }
}
