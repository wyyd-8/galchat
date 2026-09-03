package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.UserWorldSaveSnapshotDTO;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserWorldSave;
import com.me.galchat.mapper.GroupConversationDeletionMapper;
import com.me.galchat.mapper.UserWorldSaveMapper;
import com.me.galchat.mapper.VectorStoreCleanupMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseGroupConversationDeletionStoreTest {

    @Test
    void prunesWorldSaveAndDeletesVectorsBeforeRelationalRows() {
        GroupConversationDeletionMapper deletionMapper =
                mock(GroupConversationDeletionMapper.class);
        UserWorldSaveMapper saveMapper = mock(UserWorldSaveMapper.class);
        VectorStoreCleanupMapper vectorCleanupMapper =
                mock(VectorStoreCleanupMapper.class);
        DatabaseGroupConversationDeletionStore store =
                new DatabaseGroupConversationDeletionStore(
                        deletionMapper, saveMapper,
                        vectorCleanupMapper);
        UserWorldSave save = new UserWorldSave()
                .setId(11L)
                .setUserWorldId(3L)
                .setSnapshot(new UserWorldSaveSnapshotDTO()
                        .setRecentGroupTurnsByConversation(List.of(
                                new UserWorldSaveSnapshotDTO
                                        .GroupConversationTurnsSnapshot()
                                        .setConversationId(7L)))
                        .setConversationPlans(List.of(
                                new UserWorldSaveSnapshotDTO
                                        .GroupConversationPlanSnapshot()
                                        .setConversationId(7L))));
        when(saveMapper.selectOne(any())).thenReturn(save);
        when(deletionMapper.deleteConversationData(7L)).thenReturn(1);

        store.delete(new GroupConversation()
                .setId(7L)
                .setUserWorldId(3L));

        assertThat(save.getSnapshot().getRecentGroupTurnsByConversation())
                .isEmpty();
        assertThat(save.getSnapshot().getConversationPlans()).isEmpty();
        verify(saveMapper).updateById(save);
        var ordered = inOrder(vectorCleanupMapper, deletionMapper);
        ordered.verify(vectorCleanupMapper)
                .deleteGroupTopicsByConversation(7L);
        ordered.verify(deletionMapper).deleteConversationData(7L);
    }
}
