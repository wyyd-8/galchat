package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgEpilogueModels;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgEpilogueService {

    private final CocCharacterMapper characterMapper;
    private final CocCharacterProfileMapper profileMapper;
    private final GroupChatMessageMapper messageMapper;
    private final GroupConversationService conversationService;
    private final TrpgEpilogueGenerator generator;
    private final TrpgEpilogueMessageCodec codec;

    public void generateAndPersist(GroupConversation conversation) {
        requireTrpg(conversation);
        List<GroupChatMessage> history = completedPublicHistory(
                conversation.getId());
        if (history.stream().anyMatch(message -> GroupChatConstant
                .MESSAGE_EPILOGUE.equals(message.getMessageKind()))) {
            return;
        }
        List<CocCharacter> investigators = characterMapper.selectList(
                        new LambdaQueryWrapper<CocCharacter>()
                                .eq(CocCharacter::getRunId,
                                        conversation.getId())
                                .orderByAsc(CocCharacter::getId))
                .stream()
                .filter(this::isInvestigator)
                .toList();
        if (investigators.isEmpty()) {
            throw new UserRequestException("跑团缺少可生成后传的调查员");
        }
        Map<Long, CocCharacterProfile> profiles = profiles(investigators);
        List<TrpgEpilogueModels.Subject> subjects = investigators.stream()
                .map(card -> subject(card, profiles.get(card.getId())))
                .toList();
        TrpgEpilogueModels.Response response = generator.generate(
                conversation, subjects, formatHistory(history));
        List<TrpgEpilogueModels.Entry> entries = normalize(
                subjects, response);
        LocalDateTime now = LocalDateTime.now();
        GroupChatMessage message = new GroupChatMessage()
                .setConversationId(conversation.getId())
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setMessageKind(GroupChatConstant.MESSAGE_EPILOGUE)
                .setVisibility("public")
                .setContent(codec.encode(
                        new TrpgEpilogueModels.Content(1, entries)))
                .setSequenceNo(conversationService.nextSequence(
                        conversation.getId()))
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messageMapper.insert(message);
    }

    private void requireTrpg(GroupConversation conversation) {
        if (conversation == null || conversation.getId() == null
                || !GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException("只有TRPG跑团可以生成人物后传");
        }
    }

    private List<GroupChatMessage> completedPublicHistory(
            Long conversationId) {
        List<GroupChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId,
                                conversationId)
                        .eq(GroupChatMessage::getVisibility, "public")
                        .eq(GroupChatMessage::getStatus,
                                GroupChatConstant.STATUS_COMPLETED)
                        .orderByAsc(GroupChatMessage::getSequenceNo));
        return messages == null ? List.of() : messages;
    }

    private boolean isInvestigator(CocCharacter card) {
        return card != null && Set.of("PLAYER", "BOT")
                .contains(card.getActorType());
    }

    private Map<Long, CocCharacterProfile> profiles(
            List<CocCharacter> investigators) {
        List<Long> ids = investigators.stream()
                .map(CocCharacter::getId)
                .toList();
        List<CocCharacterProfile> rows = profileMapper.selectList(
                new LambdaQueryWrapper<CocCharacterProfile>()
                        .in(CocCharacterProfile::getCharacterId, ids));
        if (rows == null) {
            return Map.of();
        }
        return rows.stream().collect(Collectors.toMap(
                CocCharacterProfile::getCharacterId,
                Function.identity(), (first, ignored) -> first,
                LinkedHashMap::new));
    }

    private TrpgEpilogueModels.Subject subject(
            CocCharacter card, CocCharacterProfile profile) {
        return new TrpgEpilogueModels.Subject(
                card.getId(), card.getName(), card.getOccupation(),
                card.getHpCurrent(), card.getHpMax(),
                card.getSanCurrent(), card.getSanMax(),
                Boolean.TRUE.equals(card.getDead()),
                Boolean.TRUE.equals(card.getDying()),
                Boolean.TRUE.equals(card.getUnconscious()),
                Boolean.TRUE.equals(card.getMajorWound()),
                Boolean.TRUE.equals(card.getTemporaryInsanity()),
                profile == null ? null : profile.getInjuriesAndScars(),
                profile == null ? null : profile.getPhobiasAndManias(),
                profile == null ? null : profile.getIdeology(),
                profile == null ? null : profile.getSignificantPeople(),
                profile == null ? null : profile.getMeaningfulLocations(),
                profile == null ? null : profile.getTreasuredPossessions(),
                profile == null ? null : profile.getTraits(),
                profile == null ? null : profile.getKeyConnectionText());
    }

    private String formatHistory(List<GroupChatMessage> history) {
        StringBuilder result = new StringBuilder();
        for (GroupChatMessage message : history) {
            if (GroupChatConstant.MESSAGE_EPILOGUE.equals(
                    message.getMessageKind())) {
                continue;
            }
            result.append('[').append(message.getSpeakerType())
                    .append("] ")
                    .append(message.getContent() == null
                            ? "" : message.getContent())
                    .append('\n');
        }
        return result.toString();
    }

    private List<TrpgEpilogueModels.Entry> normalize(
            List<TrpgEpilogueModels.Subject> subjects,
            TrpgEpilogueModels.Response response) {
        if (response == null || response.entries() == null) {
            throw invalidResponse();
        }
        Map<Long, TrpgEpilogueModels.Entry> byCharacter =
                new LinkedHashMap<>();
        for (TrpgEpilogueModels.Entry entry : response.entries()) {
            if (entry == null || entry.characterId() == null
                    || !StringUtils.hasText(entry.content())
                    || byCharacter.putIfAbsent(
                    entry.characterId(), entry) != null) {
                throw invalidResponse();
            }
        }
        Set<Long> expectedIds = subjects.stream()
                .map(TrpgEpilogueModels.Subject::characterId)
                .collect(Collectors.toSet());
        if (!byCharacter.keySet().equals(expectedIds)) {
            throw invalidResponse();
        }
        return subjects.stream()
                .map(subject -> new TrpgEpilogueModels.Entry(
                        subject.characterId(),
                        subject.investigatorName(),
                        byCharacter.get(subject.characterId())
                                .content().trim()))
                .toList();
    }

    private UserRequestException invalidResponse() {
        return new UserRequestException(
                "AI未能为每位调查员生成有效的人物后传");
    }
}
