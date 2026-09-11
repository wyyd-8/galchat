package com.me.galchat.groupchat.decision;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.po.GroupChatAgentDecision;
import com.me.galchat.mapper.GroupChatAgentDecisionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GroupAgentDecisionStore {

    private final GroupChatAgentDecisionMapper mapper;

    @Transactional(rollbackFor = Exception.class)
    public GroupChatAgentDecision save(
            Long replyStepId, String content) {
        LocalDateTime now = LocalDateTime.now();
        GroupChatAgentDecision decision =
                new GroupChatAgentDecision()
                        .setReplyStepId(replyStepId)
                        .setContent(content)
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
        mapper.insert(decision);
        return decision;
    }

    public Map<Long, String> contentByReplyStepIds(
            Collection<Long> replyStepIds) {
        if (replyStepIds == null || replyStepIds.isEmpty()) {
            return Map.of();
        }
        List<GroupChatAgentDecision> rows = mapper.selectList(
                new LambdaQueryWrapper<GroupChatAgentDecision>()
                        .in(GroupChatAgentDecision::getReplyStepId,
                                replyStepIds));
        Map<Long, String> result = new LinkedHashMap<>();
        for (GroupChatAgentDecision row : rows) {
            result.put(row.getReplyStepId(), row.getContent());
        }
        return Map.copyOf(result);
    }

    public List<String> completedContentForActorContext(
            Long conversationId,
            String actionType,
            Long actorId,
            String groupKey) {
        return mapper.selectCompletedForActorContext(
                        conversationId,
                        actionType,
                        actorId,
                        groupKey)
                .stream()
                .map(GroupChatAgentDecision::getContent)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteByReplyStepId(Long replyStepId) {
        mapper.delete(new LambdaQueryWrapper<GroupChatAgentDecision>()
                .eq(GroupChatAgentDecision::getReplyStepId,
                        replyStepId));
    }
}
