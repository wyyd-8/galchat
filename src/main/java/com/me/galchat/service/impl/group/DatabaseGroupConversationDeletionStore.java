package com.me.galchat.service.impl.group;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserWorldSave;
import com.me.galchat.mapper.GroupConversationDeletionMapper;
import com.me.galchat.mapper.UserWorldSaveMapper;
import com.me.galchat.mapper.VectorStoreCleanupMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DatabaseGroupConversationDeletionStore
        implements GroupConversationDeletionStore {

    private final GroupConversationDeletionMapper deletionMapper;
    private final UserWorldSaveMapper userWorldSaveMapper;
    private final VectorStoreCleanupMapper vectorStoreCleanupMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(GroupConversation conversation) {
        Long conversationId = conversation.getId();
        pruneWorldSave(conversation.getUserWorldId(), conversationId);
        vectorStoreCleanupMapper.deleteGroupTopicsByConversation(
                conversationId);
        vectorStoreCleanupMapper.deleteTrpgTurnsByConversation(
                conversationId);
        int deleted = deletionMapper.deleteConversationData(conversationId);
        if (deleted != 1) {
            throw new IllegalStateException("删除群聊会话失败");
        }
    }

    private void pruneWorldSave(Long userWorldId, Long conversationId) {
        UserWorldSave save = userWorldSaveMapper.selectOne(
                new LambdaQueryWrapper<UserWorldSave>()
                        .eq(UserWorldSave::getUserWorldId, userWorldId)
                        .last("limit 1"));
        if (save == null || save.getSnapshot() == null) {
            return;
        }
        if (GroupConversationSaveSnapshotPruner.prune(
                save.getSnapshot(), conversationId)) {
            userWorldSaveMapper.updateById(save);
        }
    }
}
