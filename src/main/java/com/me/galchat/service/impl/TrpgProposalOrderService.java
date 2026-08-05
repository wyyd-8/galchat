package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrpgProposalOrderService {

    private final TrpgProposalOrderStore store;
    private final GroupChatTurnMapper turnMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final TrpgParticipantService participantService;

    public List<GroupActionSpec> orderForTurn(
            GroupConversation conversation,
            List<GroupActionSpec> actions) {
        if (conversation == null || conversation.getId() == null
                || actions == null || actions.isEmpty()) {
            return actions == null ? List.of() : List.copyOf(actions);
        }
        Long latestTurnId = turnMapper
                .selectLatestCompletedSceneProposalTurnId(
                        conversation.getId());
        TrpgProposalOrderStore.State state = safeLoad(
                conversation.getId())
                .filter(value -> java.util.Objects.equals(
                        value.cursorTurnId(), latestTurnId))
                .orElse(null);
        if (state == null) {
            state = rebuild(conversation, latestTurnId);
            safeSave(conversation.getId(), state);
        }
        List<String> queue = new ArrayList<>(state.actorKeys());
        boolean queueChanged = false;
        for (GroupActionSpec action : actions) {
            if (isInvestigator(action)) {
                String key = actorKey(action);
                if (!queue.contains(key)) {
                    queue.add(key);
                    queueChanged = true;
                }
            }
        }
        if (queueChanged) {
            state = new TrpgProposalOrderStore.State(
                    state.cursorTurnId(), queue);
            safeSave(conversation.getId(), state);
        }
        Map<String, Integer> rank = new HashMap<>();
        for (int index = 0; index < state.actorKeys().size(); index++) {
            rank.putIfAbsent(state.actorKeys().get(index), index);
        }
        List<GroupActionSpec> ordered = new ArrayList<>(actions);
        ordered.sort(Comparator.comparingInt(action ->
                GroupChatConstant.ACTOR_KP.equals(action.actorType())
                        ? Integer.MAX_VALUE
                        : rank.getOrDefault(actorKey(action),
                        Integer.MAX_VALUE - 1)));
        List<GroupActionSpec> normalized = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            GroupActionSpec action = ordered.get(index);
            normalized.add(new GroupActionSpec(
                    action.actionType(), action.actorType(),
                    action.actorId(), action.subjectCharacterId(),
                    action.groupKey(), action.groupName(),
                    action.groupOrder(), index + 1));
        }
        return List.copyOf(normalized);
    }

    public void onTurnCompleted(
            GroupConversation conversation, GroupChatTurn turn) {
        if (conversation == null || conversation.getId() == null
                || turn == null || turn.getId() == null
                || !GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                        turn.getPlanSource())) {
            return;
        }
        Long latestTurnId = turnMapper
                .selectLatestCompletedSceneProposalTurnId(
                        conversation.getId());
        TrpgProposalOrderStore.State state = safeLoad(
                conversation.getId())
                .filter(value -> java.util.Objects.equals(
                        value.cursorTurnId(), latestTurnId))
                .orElseGet(() -> rebuild(conversation, latestTurnId));
        List<GroupChatReplyStep> completed = stepMapper.selectList(
                new LambdaQueryWrapper<GroupChatReplyStep>()
                        .eq(GroupChatReplyStep::getTurnId, turn.getId())
                        .eq(GroupChatReplyStep::getActionType,
                                GroupChatConstant.ACTION_TRPG_SCENE)
                        .in(GroupChatReplyStep::getSpeakerType,
                                GroupChatConstant.ACTOR_USER,
                                GroupChatConstant.ACTOR_CHARACTER)
                        .eq(GroupChatReplyStep::getStatus,
                                GroupChatConstant.STATUS_COMPLETED)
                        .orderByAsc(GroupChatReplyStep::getStepNo));
        if (completed == null || completed.isEmpty()) {
            return;
        }
        GroupChatReplyStep lead = completed.getFirst();
        List<String> queue = new ArrayList<>(state.actorKeys());
        moveToBack(queue,
                lead.getSpeakerType() + ":" + lead.getSpeakerId());
        safeSave(conversation.getId(),
                new TrpgProposalOrderStore.State(turn.getId(), queue));
    }

    private String actorKey(GroupActionSpec action) {
        return action.actorType() + ":" + action.actorId();
    }

    private boolean isInvestigator(GroupActionSpec action) {
        return GroupChatConstant.ACTOR_USER.equals(action.actorType())
                || GroupChatConstant.ACTOR_CHARACTER.equals(
                action.actorType());
    }

    private TrpgProposalOrderStore.State rebuild(
            GroupConversation conversation, Long latestTurnId) {
        List<String> queue = new ArrayList<>();
        for (TrpgParticipantService.Participant participant :
                participantService.listInvestigators(conversation)) {
            queue.add(TrpgSceneProgressStore.actorKey(
                    participant.actor()));
        }
        List<GroupChatTurn> turns = turnMapper.selectList(
                new LambdaQueryWrapper<GroupChatTurn>()
                        .eq(GroupChatTurn::getConversationId,
                                conversation.getId())
                        .eq(GroupChatTurn::getPlanSource,
                                GroupChatConstant.PLAN_SOURCE_SCENE)
                        .eq(GroupChatTurn::getStatus,
                                GroupChatConstant.STATUS_COMPLETED)
                        .orderByAsc(GroupChatTurn::getId));
        List<Long> turnIds = turns == null ? List.of()
                : turns.stream().map(GroupChatTurn::getId).toList();
        if (!turnIds.isEmpty()) {
            List<GroupChatReplyStep> steps = stepMapper.selectList(
                    new LambdaQueryWrapper<GroupChatReplyStep>()
                            .in(GroupChatReplyStep::getTurnId, turnIds)
                            .eq(GroupChatReplyStep::getActionType,
                                    GroupChatConstant.ACTION_TRPG_SCENE)
                            .in(GroupChatReplyStep::getSpeakerType,
                                    GroupChatConstant.ACTOR_USER,
                                    GroupChatConstant.ACTOR_CHARACTER)
                            .eq(GroupChatReplyStep::getStatus,
                                    GroupChatConstant.STATUS_COMPLETED)
                            .orderByAsc(GroupChatReplyStep::getTurnId)
                            .orderByAsc(GroupChatReplyStep::getStepNo));
            Map<Long, GroupChatReplyStep> leadByTurn =
                    new LinkedHashMap<>();
            if (steps != null) {
                for (GroupChatReplyStep step : steps) {
                    leadByTurn.putIfAbsent(step.getTurnId(), step);
                }
            }
            for (GroupChatTurn turn : turns) {
                GroupChatReplyStep lead = leadByTurn.get(turn.getId());
                if (lead != null) {
                    moveToBack(queue, lead.getSpeakerType()
                            + ":" + lead.getSpeakerId());
                }
            }
        }
        return new TrpgProposalOrderStore.State(
                latestTurnId, queue);
    }

    private void moveToBack(List<String> queue, String actorKey) {
        if (queue.remove(actorKey)) {
            queue.add(actorKey);
        }
    }

    private Optional<TrpgProposalOrderStore.State> safeLoad(
            Long conversationId) {
        try {
            return store.load(conversationId);
        } catch (RuntimeException exception) {
            log.warn("读取TRPG提案顺序缓存失败，改从Turn历史重建: "
                            + "conversationId={}, cause={}",
                    conversationId, exception.toString());
            return Optional.empty();
        }
    }

    private void safeSave(
            Long conversationId,
            TrpgProposalOrderStore.State state) {
        try {
            store.save(conversationId, state);
        } catch (RuntimeException exception) {
            log.warn("写入TRPG提案顺序缓存失败，本轮继续执行: "
                            + "conversationId={}, cause={}",
                    conversationId, exception.toString());
            try {
                store.evict(conversationId);
            } catch (RuntimeException evictFailure) {
                log.warn("清理TRPG提案顺序缓存失败: conversationId={}, "
                                + "cause={}",
                        conversationId, evictFailure.toString());
            }
        }
    }
}
