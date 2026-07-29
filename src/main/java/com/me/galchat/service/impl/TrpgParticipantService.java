package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.CocCharacterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgParticipantService {

    private final GroupConversationService conversationService;
    private final CocCharacterMapper characterMapper;

    public List<Participant> listInvestigators(
            GroupConversation conversation) {
        if (conversation == null || conversation.getId() == null
                || conversation.getUserWorldId() == null) {
            throw new UserRequestException("跑团会话不能为空");
        }
        List<CocCharacter> cards = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId,
                                conversation.getUserWorldId())
                        .orderByAsc(CocCharacter::getId));
        List<CocCharacter> playerCards = cards.stream()
                .filter(card -> "PLAYER".equals(card.getActorType()))
                .filter(card -> card.getParticipantId() == null)
                .toList();
        if (playerCards.size() != 1) {
            throw new UserRequestException(
                    "跑团必须且只能存在一张用户调查员人物卡");
        }
        CocCharacter player = playerCards.getFirst();
        List<Participant> result = new ArrayList<>();
        result.add(new Participant(
                new GroupActorRef(
                        GroupChatConstant.ACTOR_USER, player.getId()),
                player.getId(), player.getName(), "用户"));

        Map<Long, CocCharacter> botCards = cards.stream()
                .filter(card -> "BOT".equals(card.getActorType()))
                .filter(card -> card.getParticipantId() != null)
                .collect(Collectors.toMap(
                        CocCharacter::getParticipantId,
                        Function.identity(),
                        (first, ignored) -> first));
        for (GroupChatMember member :
                conversationService.listMembers(conversation.getId())) {
            if (!Boolean.TRUE.equals(member.getEnabled())
                    || !GroupChatConstant.ACTOR_CHARACTER.equals(
                    member.getActorType())) {
                continue;
            }
            CocCharacter card = botCards.get(member.getActorId());
            if (card == null) {
                throw new UserRequestException(
                        "调查员缺少人物卡：" + member.getActorId());
            }
            result.add(new Participant(
                    new GroupActorRef(
                            GroupChatConstant.ACTOR_CHARACTER,
                            member.getActorId()),
                    card.getId(), card.getName(), card.getPlayerName()));
        }
        return List.copyOf(result);
    }

    public record Participant(
            GroupActorRef actor,
            Long cardId,
            String investigatorName,
            String controllerName) {
    }
}
