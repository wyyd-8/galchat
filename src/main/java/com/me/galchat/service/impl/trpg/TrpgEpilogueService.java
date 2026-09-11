package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgCompletionModels.Materials;
import com.me.galchat.domain.dto.TrpgEpilogueModels;
import com.me.galchat.domain.po.*;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.service.impl.group.GroupConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgEpilogueService {
    private final CocCharacterProfileMapper profileMapper;
    private final GroupChatMessageMapper messageMapper;
    private final GroupConversationService conversationService;
    private final TrpgEpilogueGenerator generator;
    private final TrpgEpilogueMessageCodec codec;

    public List<TrpgEpilogueModels.Subject> subjects(List<CocCharacter> investigators) {
        if (investigators.isEmpty()) {
            throw new UserRequestException("跑团缺少可生成后传的调查员");
        }
        Map<Long, CocCharacterProfile> profiles = profiles(investigators);
        return investigators.stream().map(card -> subject(card, profiles.get(card.getId()))).toList();
    }

    public List<TrpgEpilogueModels.Entry> generate(ChatClient client, GroupConversation conversation, Materials materials) {
        var subjects = materials.investigators().stream().map(person -> person.subject()).toList();
        String history = materials.sources().stream().map(source -> source.text())
                .collect(Collectors.joining("\n\n"));
        return normalize(subjects, generator.generate(client, conversation, subjects, history));
    }

    // Called only by the final summary-turn transaction.
    public void persist(GroupConversation conversation, List<TrpgEpilogueModels.Entry> entries,
                        Long turnId, Long stepId) {
        Long count = messageMapper.selectCount(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getConversationId, conversation.getId())
                .eq(GroupChatMessage::getMessageKind, GroupChatConstant.MESSAGE_EPILOGUE)
                .eq(GroupChatMessage::getVisibility, "public")
                .eq(GroupChatMessage::getStatus, GroupChatConstant.STATUS_COMPLETED));
        if (count != null && count > 0) return;
        LocalDateTime now = LocalDateTime.now();
        messageMapper.insert(new GroupChatMessage()
                .setConversationId(conversation.getId()).setTurnId(turnId).setReplyStepId(stepId)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setMessageKind(GroupChatConstant.MESSAGE_EPILOGUE)
                .setVisibility("public")
                .setContent(codec.encode(new TrpgEpilogueModels.Content(1, entries)))
                .setSequenceNo(conversationService.nextSequence(conversation.getId()))
                .setStatus(GroupChatConstant.STATUS_COMPLETED).setCreatedAt(now).setUpdatedAt(now));
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
                    || !StringUtils.hasText(entry.lead())
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
                        byCharacter.get(subject.characterId()).lead().trim(),
                        byCharacter.get(subject.characterId())
                                .content().trim()))
                .toList();
    }

    private UserRequestException invalidResponse() {
        return new UserRequestException(
                "AI未能为每位调查员生成有效的人物后传");
    }
}
