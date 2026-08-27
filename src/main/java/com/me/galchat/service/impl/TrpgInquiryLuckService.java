package com.me.galchat.service.impl;

import com.me.galchat.constant.CocCheckDifficulty;
import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpDiceRequestDTOs;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.tool.GroupToolCallStore;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.service.ICocDiceOrchestrationService;
import com.me.galchat.service.ICharacterCardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgInquiryLuckService {

    private static final Set<String> SCOPES = Set.of(
            "REQUESTER", "CURRENT_INVESTIGATOR_GROUP");

    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatTurnMapper turnMapper;
    private final TrpgParticipantService participantService;
    private final TrpgSceneParticipantService sceneParticipantService;
    private final ICharacterCardService characterCardService;
    private final ICocDiceOrchestrationService orchestrationService;
    private final GroupToolCallStore toolCallStore;

    public KpDiceToolResult request(
            GroupConversation conversation,
            Long turnId,
            Long replyStepId,
            String rawFavorableEvent,
            String rawScope) {
        String favorableEvent = required(
                rawFavorableEvent, "有利事件");
        String scope = required(rawScope, "幸运范围")
                .toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw new UserRequestException(
                    "幸运范围只能是REQUESTER或CURRENT_INVESTIGATOR_GROUP");
        }
        if (favorableEvent.length() > 160) {
            throw new UserRequestException("有利事件不能超过160个字符");
        }
        InquiryContext inquiry = requireInquiryContext(
                conversation, turnId, replyStepId);
        if (toolCallStore.hasExecution(
                replyStepId,
                DiceRollConstant.TOOL_REQUEST_INQUIRY_LUCK)) {
            throw new UserRequestException(
                    "一次KP询问回答最多进行一次幸运检定");
        }
        TrpgParticipantService.Participant target =
                "REQUESTER".equals(scope)
                        ? inquiry.requester()
                        : lowestLuckActiveInvestigator(
                                conversation, inquiry.participants());
        KpDiceRequestDTOs.Check request = new KpDiceRequestDTOs.Check(
                "判断外部偶然事件：" + favorableEvent,
                CocCheckDifficulty.REGULAR,
                new KpDiceRequestDTOs.CheckTarget(
                        target.investigatorName(),
                        List.of("幸运"),
                        CocPercentileModifier.NORMAL));
        return orchestrationService.requestCheck(
                conversation.getId(), conversation.getId(), request);
    }

    private InquiryContext requireInquiryContext(
            GroupConversation conversation,
            Long turnId,
            Long replyStepId) {
        if (conversation == null || conversation.getId() == null
                || !GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException("幸运询问只适用于跑团会话");
        }
        GroupChatTurn turn = turnMapper.selectById(turnId);
        if (turn == null
                || !conversation.getId().equals(
                turn.getConversationId())) {
            throw new UserRequestException("幸运询问行动轮不存在");
        }
        GroupChatReplyStep child = stepMapper.selectById(replyStepId);
        if (child == null || !turnId.equals(child.getTurnId())
                || !GroupChatConstant.ACTOR_KP.equals(
                child.getSpeakerType())
                || !GroupChatConstant
                .ACTION_TRPG_INTERACTION_RESPONSE.equals(
                        child.getActionType())
                || !TrpgStepInteractionService
                .INVESTIGATOR_KP_INQUIRY.equals(
                        child.getInteractionType())
                || child.getParentStepId() == null
                || !GroupChatConstant.STATUS_RUNNING.equals(
                        child.getStatus())) {
            throw new UserRequestException(
                    "当前步骤不是KP回答调查员询问");
        }
        GroupChatReplyStep parent = stepMapper.selectById(
                child.getParentStepId());
        if (parent == null
                || !turnId.equals(parent.getTurnId())
                || parent.getParentStepId() != null
                || !java.util.Objects.equals(
                        child.getRootStepId(), parent.getId())
                || !java.util.Objects.equals(
                        child.getSubjectCharacterId(),
                        parent.getSubjectCharacterId())
                || !TrpgStepInteractionService
                .INVESTIGATOR_KP_INQUIRY.equals(
                        parent.getInteractionType())
                || !GroupChatConstant.STATUS_WAITING_INTERACTION.equals(
                        parent.getStatus())
                || !(GroupChatConstant.ACTOR_CHARACTER.equals(
                parent.getSpeakerType())
                || GroupChatConstant.ACTOR_USER.equals(
                parent.getSpeakerType()))
                || !(GroupChatConstant.ACTION_TRPG_SCENE.equals(
                parent.getActionType())
                || GroupChatConstant.ACTION_COMBAT_ATTACK.equals(
                parent.getActionType()))) {
            throw new UserRequestException(
                    "调查员询问的原行动步骤无效");
        }
        List<TrpgParticipantService.Participant> participants =
                participantService.listInvestigators(conversation);
        GroupActorRef requesterActor = new GroupActorRef(
                parent.getSpeakerType(), parent.getSpeakerId());
        TrpgParticipantService.Participant requester =
                participants.stream()
                        .filter(item -> requesterActor.equals(item.actor()))
                        .filter(item -> java.util.Objects.equals(
                                parent.getSubjectCharacterId(),
                                item.cardId()))
                        .findFirst()
                        .orElseThrow(() -> new UserRequestException(
                                "询问发起者与调查员人物卡不匹配"));
        return new InquiryContext(requester, participants);
    }

    private TrpgParticipantService.Participant
            lowestLuckActiveInvestigator(
            GroupConversation conversation,
            List<TrpgParticipantService.Participant> participants) {
        Set<Long> activeIds = Set.copyOf(
                sceneParticipantService.state(conversation)
                        .activeInvestigatorCharacterIds());
        Map<Long, CocDiceCharacterVO> cards = characterCardService
                .listDiceCharacters(conversation.getId()).stream()
                .collect(Collectors.toMap(
                        CocDiceCharacterVO::cardId,
                        Function.identity(),
                        (first, ignored) -> first));
        return participants.stream()
                .filter(item -> activeIds.contains(item.cardId()))
                .min(Comparator
                        .comparingInt((TrpgParticipantService.Participant item) ->
                                luckValue(cards.get(item.cardId())))
                        .thenComparing(
                                TrpgParticipantService.Participant::cardId))
                .orElseThrow(() -> new UserRequestException(
                        "当前场景没有可进行团体幸运的调查员"));
    }

    private int luckValue(CocDiceCharacterVO card) {
        if (card == null || card.checkValues() == null) {
            throw new UserRequestException("调查员缺少幸运值");
        }
        Integer luck = card.checkValues().get("幸运");
        if (luck == null) {
            luck = card.checkValues().get("LUCK");
        }
        if (luck == null) {
            throw new UserRequestException(
                    "调查员缺少幸运值：" + card.name());
        }
        return luck;
    }

    private String required(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new UserRequestException(label + "不能为空");
        }
        return value.trim();
    }

    private record InquiryContext(
            TrpgParticipantService.Participant requester,
            List<TrpgParticipantService.Participant> participants) {
    }
}
