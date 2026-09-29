package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.utils.RedisAfterCommitCleanup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TrpgMaterialRecoveryService {
    private final GroupChatMessageMapper messageMapper;
    private final TrpgMaterialStateStore stateStore;
    private final ObjectMapper objectMapper;

    /** Called before deleting the message suffix, inside the recovery transaction. */
    public void restoreAfterCheckpoint(Long conversationId, Long replyStepId, long messageId) {
        Set<Long> removed = new HashSet<>();
        Set<Long> retained = new HashSet<>();
        for (GroupChatMessage message : messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId, conversationId)
                        .eq(GroupChatMessage::getMessageKind, GroupChatConstant.MESSAGE_MATERIAL)
                        .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_COMPLETED))) {
            Long materialId = materialId(message);
            if (materialId == null) continue;
            if (Objects.equals(replyStepId, message.getReplyStepId())
                    && message.getId() > messageId) {
                removed.add(materialId);
            } else {
                retained.add(materialId);
            }
        }
        for (Long materialId : removed) {
            RedisAfterCommitCleanup.run("恢复材料展示状态", () -> {
                if (retained.contains(materialId)) {
                    stateStore.markShown(conversationId, materialId);
                } else {
                    stateStore.unmarkShown(conversationId, materialId);
                }
            });
        }
    }

    private Long materialId(GroupChatMessage message) {
        try {
            Object value = objectMapper.readValue(message.getContent(), Map.class).get("materialId");
            return value instanceof Number number ? number.longValue() : null;
        } catch (JacksonException exception) {
            // Legacy malformed messages cannot identify a cache entry to invalidate.
            return null;
        }
    }
}
