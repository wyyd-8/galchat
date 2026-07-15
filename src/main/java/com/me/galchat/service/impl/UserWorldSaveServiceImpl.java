package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.dto.UserWorldSaveCreateDTO;
import com.me.galchat.domain.dto.UserWorldSaveSnapshotDTO;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatThinkingHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.domain.po.UserEventLog;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.UserWorldSave;
import com.me.galchat.domain.po.WorldEventLog;
import com.me.galchat.domain.vo.UserWorldSaveOverviewVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.UserCharacterFavorLogMapper;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatThinkingHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import com.me.galchat.mapper.UserEventLogMapper;
import com.me.galchat.mapper.UserWorldSaveMapper;
import com.me.galchat.mapper.UserWorldSaveRestoreMapper;
import com.me.galchat.mapper.VectorStoreCleanupMapper;
import com.me.galchat.mapper.WorldEventLogMapper;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.IUserWorldSaveService;
import com.me.galchat.vector.WorldEventVectorService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.redisson.api.RLock;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserWorldSaveServiceImpl implements IUserWorldSaveService {

    private static final int FORMAT_VERSION = 1;
    private static final int RECENT_ROUND_COUNT = 3;

    private final IUserWorldPrefixService userWorldPrefixService;
    private final UserWorldSaveMapper userWorldSaveMapper;
    private final UserWorldSaveRestoreMapper userWorldSaveRestoreMapper;
    private final UserCharacterInfoMapper userCharacterInfoMapper;
    private final CharacterTemplateMapper characterTemplateMapper;
    private final UserChatHistoryMapper userChatHistoryMapper;
    private final UserChatThinkingHistoryMapper userChatThinkingHistoryMapper;
    private final UserChatToolCallMapper userChatToolCallMapper;
    private final UserCharacterFavorLogMapper userCharacterFavorLogMapper;
    private final UserEventLogMapper userEventLogMapper;
    private final WorldEventLogMapper worldEventLogMapper;
    private final GroupConversationMapper groupConversationMapper;
    private final GroupChatMessageMapper groupChatMessageMapper;
    private final GroupChatTurnMapper groupChatTurnMapper;
    private final GroupChatReplyStepMapper groupChatReplyStepMapper;
    private final GroupContextSummaryMapper groupContextSummaryMapper;
    private final GroupReplyPlanService groupReplyPlanService;
    private final SingleChatLockService singleChatLockService;
    private final GroupConversationLockService groupConversationLockService;
    private final StringRedisTemplate redisTemplate;
    private final VectorStoreCleanupMapper vectorStoreCleanupMapper;
    private final WorldEventVectorService worldEventVectorService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public UserWorldSaveOverviewVO getSave(Long userId, Long userWorldId) {
        userWorldPrefixService.checkUserWorldAuth(userId, userWorldId, false);
        UserWorldSave save = getUserWorldSave(userId, userWorldId);
        if (save == null) {
            return null;
        }
        return toOverview(save);
    }

    @Override
    public UserWorldSaveOverviewVO saveWorld(Long userId, Long userWorldId, UserWorldSaveCreateDTO createDTO) {
        UserWorldPrefix userWorld = userWorldPrefixService.checkUserWorldAuth(userId, userWorldId, true);
        List<UserCharacterInfo> characters = listCharacters(userWorldId);
        List<RLock> locks = singleChatLockService.lockConversations(userWorldId, characterIds(characters));
        List<GroupConversationLockService.OwnedLock> groupLocks = List.of();
        try {
            groupLocks = lockActiveGroupConversations(userWorldId);
            UserWorldSave saved = transactionTemplate.execute(status -> doSaveWorld(userId, userWorld, createDTO));
            return toOverview(saved);
        } finally {
            unlockGroupConversations(groupLocks);
            singleChatLockService.unlockAll(locks);
        }
    }

    @Override
    public void loadWorld(Long userId, Long userWorldId) {
        UserWorldSave save = getRequiredSave(userId, userWorldId);
        UserWorldPrefix userWorld = userWorldPrefixService.checkUserWorldAuth(userId, userWorldId, true);
        UserWorldSaveSnapshotDTO snapshot = save.getSnapshot();
        validateSnapshot(userWorld, snapshot);

        List<UserCharacterInfo> characters = listCharacters(userWorldId);
        List<RLock> locks = singleChatLockService.lockConversations(userWorldId, characterIds(characters));
        List<GroupConversationLockService.OwnedLock> groupLocks = List.of();
        try {
            groupLocks = lockActiveGroupConversations(userWorldId);
            List<Long> deletedUserMessageIds = transactionTemplate.execute(status -> doLoadWorld(userWorldId, snapshot));
            evictRedisData(userWorldId, emptyIfNull(deletedUserMessageIds), snapshot);
            restoreTopicBoundaries(userWorldId, snapshot.getTopicBoundaries());
            restoreDerivedData(userWorldId, snapshot);
        } finally {
            unlockGroupConversations(groupLocks);
            singleChatLockService.unlockAll(locks);
        }
    }

    protected UserWorldSave doSaveWorld(Long userId, UserWorldPrefix userWorld, UserWorldSaveCreateDTO createDTO) {
        List<UserCharacterInfo> characters = listCharacters(userWorld.getId());
        UserWorldSaveSnapshotDTO snapshot = buildSnapshot(userWorld, characters);
        List<UserWorldSaveOverviewVO.CharacterFavorVO> characterFavors = characterFavors(characters);
        LocalDateTime now = LocalDateTime.now();

        UserWorldSave existing = getUserWorldSave(userId, userWorld.getId());
        UserWorldSave save = new UserWorldSave()
                .setUserId(userId)
                .setUserWorldId(userWorld.getId())
                .setRemark(normalizeRemark(createDTO))
                .setSavedAt(now)
                .setFormatVersion(FORMAT_VERSION)
                .setCharacterFavors(characterFavors)
                .setSnapshot(snapshot);
        if (existing == null) {
            userWorldSaveMapper.insert(save);
            return save;
        }

        save.setId(existing.getId());
        userWorldSaveMapper.updateById(save);
        return save;
    }

    protected List<Long> doLoadWorld(Long userWorldId, UserWorldSaveSnapshotDTO snapshot) {
        List<Long> deletedUserMessageIds = listUserMessageIdsAfter(userWorldId, snapshot.getMaxChatHistoryId());
        deleteAfterSnapshot(userWorldId, snapshot);
        groupReplyPlanService.resetWorldPlans(userWorldId);
        restoreRecentChatRounds(userWorldId, snapshot);
        restoreWorldEventLog(snapshot.getLastWorldEventLog());
        restoreCharacterStates(userWorldId, snapshot.getCharacterStates());
        return deletedUserMessageIds;
    }

    private UserWorldSaveSnapshotDTO buildSnapshot(UserWorldPrefix userWorld, List<UserCharacterInfo> characters) {
        Long userWorldId = userWorld.getId();
        return new UserWorldSaveSnapshotDTO()
                .setFormatVersion(FORMAT_VERSION)
                .setUserWorldId(userWorldId)
                .setWorldId(userWorld.getWorldId())
                .setCharacterIds(characterIds(characters))
                .setMaxChatHistoryId(maxChatHistoryId(userWorldId))
                .setMaxFavorLogId(maxFavorLogId(userWorldId))
                .setMaxUserEventLogId(maxUserEventLogId(userWorldId))
                .setMaxWorldEventLogId(maxWorldEventLogId(userWorldId))
                .setMaxGroupConversationId(maxGroupConversationId(userWorldId))
                .setMaxGroupMessageId(maxGroupMessageId(userWorldId))
                .setMaxGroupTurnId(maxGroupTurnId(userWorldId))
                .setMaxGroupReplyStepId(maxGroupReplyStepId(userWorldId))
                .setMaxGroupContextSummaryId(maxGroupContextSummaryId(userWorldId))
                .setCharacterStates(characterStates(characters))
                .setTopicBoundaries(topicBoundaries(userWorldId, characters))
                .setRecentChatRoundsByCharacter(recentChatRounds(userWorldId, characters))
                .setLastWorldEventLog(lastWorldEventLog(userWorldId));
    }

    private void validateSnapshot(UserWorldPrefix userWorld, UserWorldSaveSnapshotDTO snapshot) {
        if (snapshot == null || !Objects.equals(snapshot.getFormatVersion(), FORMAT_VERSION)) {
            throw new UserRequestException("不支持的存档格式");
        }
        if (!Objects.equals(userWorld.getId(), snapshot.getUserWorldId())) {
            throw new UserRequestException("存档不属于当前用户世界");
        }
        if (!Objects.equals(userWorld.getWorldId(), snapshot.getWorldId())) {
            throw new UserRequestException("当前世界模板与存档不一致，无法读档");
        }
        List<Long> characterIds = snapshot.getCharacterIds() == null ? List.of() : snapshot.getCharacterIds();
        if (!characterIds.isEmpty()) {
            Long existingTemplateCount = characterTemplateMapper.selectCount(new LambdaQueryWrapper<CharacterTemplate>()
                    .in(CharacterTemplate::getId, characterIds)
                    .eq(CharacterTemplate::getWorldId, snapshot.getWorldId()));
            if (existingTemplateCount == null || existingTemplateCount != characterIds.size()) {
                throw new UserRequestException("存档中的角色模板已不存在，无法读档");
            }
        }
    }

    private void deleteAfterSnapshot(Long userWorldId, UserWorldSaveSnapshotDTO snapshot) {
        userWorldSaveRestoreMapper.deleteThinkingAfterChat(userWorldId, safeMax(snapshot.getMaxChatHistoryId()));
        userWorldSaveRestoreMapper.deleteToolCallsAfterChat(userWorldId, safeMax(snapshot.getMaxChatHistoryId()));
        userWorldSaveRestoreMapper.deleteChatAfter(userWorldId, safeMax(snapshot.getMaxChatHistoryId()));
        userWorldSaveRestoreMapper.deleteFavorLogsAfter(userWorldId, safeMax(snapshot.getMaxFavorLogId()));
        userWorldSaveRestoreMapper.deleteUserEventsAfter(userWorldId, safeMax(snapshot.getMaxUserEventLogId()));
        userWorldSaveRestoreMapper.deleteWorldEventsAfter(userWorldId, safeMax(snapshot.getMaxWorldEventLogId()));
        if (snapshot.getMaxGroupConversationId() != null) {
            userWorldSaveRestoreMapper.deleteGroupReplyStepsAfter(userWorldId,
                    safeMax(snapshot.getMaxGroupReplyStepId()));
            userWorldSaveRestoreMapper.deleteGroupMessagesAfter(userWorldId,
                    safeMax(snapshot.getMaxGroupMessageId()));
            userWorldSaveRestoreMapper.deleteGroupTurnsAfter(userWorldId,
                    safeMax(snapshot.getMaxGroupTurnId()));
            userWorldSaveRestoreMapper.deleteGroupSummariesAfter(userWorldId,
                    safeMax(snapshot.getMaxGroupContextSummaryId()));
            userWorldSaveRestoreMapper.deleteReplyPlanItemsAfterConversation(userWorldId,
                    safeMax(snapshot.getMaxGroupConversationId()));
            userWorldSaveRestoreMapper.deleteReplyPlansAfterConversation(userWorldId,
                    safeMax(snapshot.getMaxGroupConversationId()));
            userWorldSaveRestoreMapper.deleteGroupMembersAfterConversation(userWorldId,
                    safeMax(snapshot.getMaxGroupConversationId()));
            userWorldSaveRestoreMapper.deleteGroupConversationsAfter(userWorldId,
                    safeMax(snapshot.getMaxGroupConversationId()));
        }
    }

    private void restoreRecentChatRounds(Long userWorldId, UserWorldSaveSnapshotDTO snapshot) {
        List<UserWorldSaveSnapshotDTO.CharacterChatRoundsSnapshot> characterRounds =
                emptyIfNull(snapshot.getRecentChatRoundsByCharacter());
        for (UserWorldSaveSnapshotDTO.CharacterChatRoundsSnapshot characterRound : characterRounds) {
            Long characterId = characterRound.getCharacterId();
            for (UserWorldSaveSnapshotDTO.ChatRoundSnapshot round : emptyIfNull(characterRound.getRounds())) {
                if (roundComplete(round)) {
                    continue;
                }
                restoreChatRound(userWorldId, characterId, round);
            }
        }
    }

    private boolean roundComplete(UserWorldSaveSnapshotDTO.ChatRoundSnapshot round) {
        List<UserChatHistory> snapshotHistories = emptyIfNull(round.getHistoryRows());
        if (snapshotHistories.isEmpty()) {
            return true;
        }
        List<Long> ids = snapshotHistories.stream().map(UserChatHistory::getId).filter(Objects::nonNull).toList();
        if (ids.size() != snapshotHistories.size()) {
            return false;
        }
        Map<Long, UserChatHistory> existingById = userChatHistoryMapper.selectBatchIds(ids)
                .stream()
                .collect(Collectors.toMap(UserChatHistory::getId, Function.identity()));
        if (existingById.size() != ids.size()) {
            return false;
        }

        for (UserChatHistory snapshotHistory : snapshotHistories) {
            UserChatHistory existing = existingById.get(snapshotHistory.getId());
            if (!sameHistory(snapshotHistory, existing)) {
                return false;
            }
        }
        return true;
    }

    private boolean sameHistory(UserChatHistory left, UserChatHistory right) {
        return right != null
                && Objects.equals(left.getUserWorldId(), right.getUserWorldId())
                && Objects.equals(left.getCharacterId(), right.getCharacterId())
                && Objects.equals(left.getContent(), right.getContent())
                && Objects.equals(normalizeType(left.getType()), normalizeType(right.getType()))
                && Objects.equals(left.getUserMessageId(), right.getUserMessageId())
                && Objects.equals(left.getStepNo(), right.getStepNo())
                && Objects.equals(left.getTimestamp(), right.getTimestamp());
    }

    private void restoreChatRound(Long userWorldId, Long characterId, UserWorldSaveSnapshotDTO.ChatRoundSnapshot round) {
        Long userMessageId = round.getUserMessageId();
        if (userMessageId == null) {
            return;
        }
        List<Long> historyIds = emptyIfNull(round.getHistoryRows()).stream()
                .map(UserChatHistory::getId)
                .filter(Objects::nonNull)
                .toList();
        if (!historyIds.isEmpty()) {
            userWorldSaveRestoreMapper.deleteChatHistoryByIds(historyIds);
        }
        userWorldSaveRestoreMapper.deleteThinkingByUserMessageId(userMessageId);
        userWorldSaveRestoreMapper.deleteToolCallsByUserMessageId(userMessageId);
        userWorldSaveRestoreMapper.deleteFavorLogsByBindingChat(userWorldId, characterId, userMessageId);

        for (UserChatHistory history : emptyIfNull(round.getHistoryRows())) {
            userWorldSaveRestoreMapper.insertChatHistoryWithId(history);
        }
        for (UserChatThinkingHistory thinking : emptyIfNull(round.getThinkingRows())) {
            userWorldSaveRestoreMapper.insertThinkingWithId(thinking);
        }
        for (UserChatToolCall toolCall : emptyIfNull(round.getToolCallRows())) {
            userWorldSaveRestoreMapper.insertToolCallWithId(toolCall);
        }
        for (UserCharacterFavorLog favorLog : emptyIfNull(round.getFavorLogs())) {
            userWorldSaveRestoreMapper.insertFavorLogWithId(favorLog);
        }
    }

    private void restoreWorldEventLog(WorldEventLog worldEventLog) {
        if (worldEventLog == null || worldEventLog.getId() == null) {
            return;
        }
        userWorldSaveRestoreMapper.upsertWorldEventLogWithId(worldEventLog);
    }

    private void restoreCharacterStates(Long userWorldId,
                                        List<UserWorldSaveSnapshotDTO.CharacterStateSnapshot> characterStates) {
        for (UserWorldSaveSnapshotDTO.CharacterStateSnapshot state : emptyIfNull(characterStates)) {
            userCharacterInfoMapper.update(new UserCharacterInfo()
                            .setCharacterName(state.getCharacterName())
                            .setCharacterImage(state.getCharacterImage())
                            .setLastChatTime(state.getLastChatTime())
                            .setLastChatContent(state.getLastChatContent())
                            .setFavorValue(state.getFavorValue())
                            .setUserInfoPrompt(state.getUserInfoPrompt()),
                    new LambdaUpdateWrapper<UserCharacterInfo>()
                            .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                            .eq(UserCharacterInfo::getCharacterId, state.getCharacterId()));
        }
    }

    private void restoreDerivedData(Long userWorldId, UserWorldSaveSnapshotDTO snapshot) {
        restoreChatVectors(userWorldId, snapshot.getTopicBoundaries());
        restoreWorldEventVectors(userWorldId, snapshot);
    }

    private void restoreChatVectors(Long userWorldId, List<UserWorldSaveSnapshotDTO.TopicBoundarySnapshot> boundaries) {
        for (UserWorldSaveSnapshotDTO.TopicBoundarySnapshot boundary : emptyIfNull(boundaries)) {
            if (boundary.getPreviousStartId() == null) {
                vectorStoreCleanupMapper.deleteChatHistoryByConversation(userWorldId, boundary.getCharacterId());
                continue;
            }
            vectorStoreCleanupMapper.deleteChatHistoryByConversationAfterEnd(userWorldId, boundary.getCharacterId(),
                    boundary.getPreviousStartId());
        }
    }

    private void restoreWorldEventVectors(Long userWorldId, UserWorldSaveSnapshotDTO snapshot) {
        vectorStoreCleanupMapper.deleteWorldEventByUserWorldIdAfterLogId(userWorldId,
                safeMax(snapshot.getMaxWorldEventLogId()));
        WorldEventLog lastWorldEventLog = snapshot.getLastWorldEventLog();
        if (lastWorldEventLog == null || lastWorldEventLog.getId() == null) {
            return;
        }
        vectorStoreCleanupMapper.deleteWorldEventByLogId(userWorldId, lastWorldEventLog.getId());
        worldEventVectorService.addWorldEventLog(lastWorldEventLog);
    }

    private List<UserWorldSaveSnapshotDTO.CharacterStateSnapshot> characterStates(List<UserCharacterInfo> characters) {
        return characters.stream()
                .map(character -> new UserWorldSaveSnapshotDTO.CharacterStateSnapshot()
                        .setCharacterId(character.getCharacterId())
                        .setCharacterName(character.getCharacterName())
                        .setCharacterImage(character.getCharacterImage())
                        .setLastChatTime(character.getLastChatTime())
                        .setLastChatContent(character.getLastChatContent())
                        .setFavorValue(character.getFavorValue())
                        .setUserInfoPrompt(character.getUserInfoPrompt()))
                .toList();
    }

    private List<UserWorldSaveOverviewVO.CharacterFavorVO> characterFavors(List<UserCharacterInfo> characters) {
        return characters.stream()
                .map(character -> new UserWorldSaveOverviewVO.CharacterFavorVO()
                        .setCharacterId(character.getCharacterId())
                        .setCharacterName(character.getCharacterName())
                        .setFavorValue(character.getFavorValue()))
                .toList();
    }

    private List<UserWorldSaveSnapshotDTO.TopicBoundarySnapshot> topicBoundaries(Long userWorldId,
                                                                                List<UserCharacterInfo> characters) {
        return characters.stream()
                .map(character -> topicBoundary(userWorldId, character.getCharacterId()))
                .toList();
    }

    private UserWorldSaveSnapshotDTO.TopicBoundarySnapshot topicBoundary(Long userWorldId, Long characterId) {
        UserWorldSaveSnapshotDTO.TopicBoundarySnapshot snapshot =
                new UserWorldSaveSnapshotDTO.TopicBoundarySnapshot().setCharacterId(characterId);
        String boundaryValue = redisTemplate.opsForValue().get(topicBoundaryKey(userWorldId, characterId));
        if (StringUtils.hasText(boundaryValue)) {
            JSONObject jsonObject = new JSONObject(boundaryValue);
            snapshot.setPreviousStartId(readLong(jsonObject, ChatConstant.TOPIC_PREVIOUS_START_ID_KEY))
                    .setCurrentStartId(readLong(jsonObject, ChatConstant.TOPIC_CURRENT_START_ID_KEY))
                    .setLastCheckedMessageId(readLong(jsonObject, ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY));
        }

        return snapshot;
    }

    private void restoreTopicBoundaries(Long userWorldId,
                                        List<UserWorldSaveSnapshotDTO.TopicBoundarySnapshot> topicBoundaries) {
        for (UserWorldSaveSnapshotDTO.TopicBoundarySnapshot boundary : emptyIfNull(topicBoundaries)) {
            Long characterId = boundary.getCharacterId();
            if (boundary.getPreviousStartId() != null || boundary.getCurrentStartId() != null
                    || boundary.getLastCheckedMessageId() != null) {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put(ChatConstant.TOPIC_PREVIOUS_START_ID_KEY, boundary.getPreviousStartId());
                jsonObject.put(ChatConstant.TOPIC_CURRENT_START_ID_KEY, boundary.getCurrentStartId());
                jsonObject.put(ChatConstant.TOPIC_LAST_CHECKED_MESSAGE_ID_KEY, boundary.getLastCheckedMessageId());
                redisTemplate.opsForValue().set(topicBoundaryKey(userWorldId, characterId), jsonObject.toString());
            }
        }
    }

    private List<UserWorldSaveSnapshotDTO.CharacterChatRoundsSnapshot> recentChatRounds(
            Long userWorldId, List<UserCharacterInfo> characters) {
        List<UserWorldSaveSnapshotDTO.CharacterChatRoundsSnapshot> result = new ArrayList<>();
        for (UserCharacterInfo character : characters) {
            result.add(new UserWorldSaveSnapshotDTO.CharacterChatRoundsSnapshot()
                    .setCharacterId(character.getCharacterId())
                    .setRounds(recentChatRounds(userWorldId, character.getCharacterId())));
        }
        return result;
    }

    private List<UserWorldSaveSnapshotDTO.ChatRoundSnapshot> recentChatRounds(Long userWorldId, Long characterId) {
        List<UserChatHistory> userMessages = userChatHistoryMapper.selectList(new LambdaQueryWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .eq(UserChatHistory::getType, MessageType.USER.getValue()))
                .orderByDesc(UserChatHistory::getId)
                .last("limit " + RECENT_ROUND_COUNT));
        userMessages.sort(Comparator.comparing(UserChatHistory::getId));
        return userMessages.stream()
                .map(userMessage -> chatRound(userWorldId, characterId, userMessage.getId()))
                .toList();
    }

    private UserWorldSaveSnapshotDTO.ChatRoundSnapshot chatRound(Long userWorldId, Long characterId, Long userMessageId) {
        List<UserChatHistory> histories = roundHistories(userWorldId, characterId, userMessageId);
        List<UserChatThinkingHistory> thinkingRows = userChatThinkingHistoryMapper.selectList(
                new LambdaQueryWrapper<UserChatThinkingHistory>()
                        .eq(UserChatThinkingHistory::getUserMessageId, userMessageId)
                        .orderByAsc(UserChatThinkingHistory::getId));
        List<UserChatToolCall> toolCallRows = userChatToolCallMapper.selectList(
                new LambdaQueryWrapper<UserChatToolCall>()
                        .eq(UserChatToolCall::getUserMessageId, userMessageId)
                        .orderByAsc(UserChatToolCall::getId));
        List<UserCharacterFavorLog> favorLogs = userCharacterFavorLogMapper.selectList(
                new LambdaQueryWrapper<UserCharacterFavorLog>()
                        .eq(UserCharacterFavorLog::getUserWorldId, userWorldId)
                        .eq(UserCharacterFavorLog::getCharacterId, characterId)
                        .eq(UserCharacterFavorLog::getBindingChat, userMessageId)
                        .orderByAsc(UserCharacterFavorLog::getId));
        return new UserWorldSaveSnapshotDTO.ChatRoundSnapshot()
                .setUserMessageId(userMessageId)
                .setHistoryRows(histories)
                .setThinkingRows(thinkingRows)
                .setToolCallRows(toolCallRows)
                .setFavorLogs(favorLogs);
    }

    private List<UserChatHistory> roundHistories(Long userWorldId, Long characterId, Long userMessageId) {
        Long nextUserMessageId = nextUserMessageId(userWorldId, characterId, userMessageId);
        return userChatHistoryMapper.selectList(new LambdaQueryWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .and(wrapper -> wrapper.eq(UserChatHistory::getId, userMessageId)
                        .or()
                        .eq(UserChatHistory::getUserMessageId, userMessageId)
                        .or(autoSearchWrapper -> autoSearchWrapper
                                .eq(UserChatHistory::getType, ChatConstant.AUTO_SEARCH_INFO_TYPE)
                                .gt(UserChatHistory::getId, userMessageId)
                                .lt(nextUserMessageId != null, UserChatHistory::getId, nextUserMessageId)))
                .orderByAsc(UserChatHistory::getId));
    }

    private Long nextUserMessageId(Long userWorldId, Long characterId, Long userMessageId) {
        UserChatHistory nextUserMessage = userChatHistoryMapper.selectOne(new LambdaQueryWrapper<UserChatHistory>()
                .select(UserChatHistory::getId)
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .gt(UserChatHistory::getId, userMessageId)
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .eq(UserChatHistory::getType, MessageType.USER.getValue())
                        .or()
                        .eq(UserChatHistory::getType, ChatConstant.WITHDRAWN_TYPE))
                .orderByAsc(UserChatHistory::getId)
                .last("limit 1"));
        return nextUserMessage == null ? null : nextUserMessage.getId();
    }

    private WorldEventLog lastWorldEventLog(Long userWorldId) {
        return worldEventLogMapper.selectOne(new LambdaQueryWrapper<WorldEventLog>()
                .eq(WorldEventLog::getUserWorldId, userWorldId)
                .orderByDesc(WorldEventLog::getId)
                .last("limit 1"));
    }

    private void evictRedisData(Long userWorldId, List<Long> deletedUserMessageIds, UserWorldSaveSnapshotDTO snapshot) {
        String worldFieldPrefix = userWorldId + ":";
        deleteHashFieldsByPattern(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, worldFieldPrefix + "*");
        deleteKeysByPattern(RedisConstant.CHAT_KEY_PREFIX + worldFieldPrefix + "*");
        deleteKeysByPattern(RedisConstant.USER_CHARACTER_PROMPT_INFO_KEY_PREFIX + worldFieldPrefix + "*");
        deleteKeysByPattern(RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + worldFieldPrefix + "*");

        Set<Long> userMessageIds = new HashSet<>(deletedUserMessageIds);
        emptyIfNull(snapshot.getRecentChatRoundsByCharacter()).forEach(characterRound ->
                emptyIfNull(characterRound.getRounds()).stream()
                        .map(UserWorldSaveSnapshotDTO.ChatRoundSnapshot::getUserMessageId)
                        .filter(Objects::nonNull)
                        .forEach(userMessageIds::add));
        if (!userMessageIds.isEmpty()) {
            redisTemplate.delete(userMessageIds.stream()
                    .map(userMessageId -> RedisConstant.CHAT_MEMORY_STEP_KEY_PREFIX + userMessageId)
                    .toList());
        }
    }

    private List<Long> listUserMessageIdsAfter(Long userWorldId, Long maxChatHistoryId) {
        return userChatHistoryMapper.selectList(new LambdaQueryWrapper<UserChatHistory>()
                .select(UserChatHistory::getId)
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .gt(UserChatHistory::getId, safeMax(maxChatHistoryId))
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .eq(UserChatHistory::getType, MessageType.USER.getValue())))
                .stream()
                .map(UserChatHistory::getId)
                .toList();
    }

    private List<UserCharacterInfo> listCharacters(Long userWorldId) {
        return userCharacterInfoMapper.selectList(new LambdaQueryWrapper<UserCharacterInfo>()
                .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                .orderByAsc(UserCharacterInfo::getCharacterId));
    }

    private List<Long> characterIds(List<UserCharacterInfo> characters) {
        return characters.stream()
                .map(UserCharacterInfo::getCharacterId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private Long maxChatHistoryId(Long userWorldId) {
        UserChatHistory row = userChatHistoryMapper.selectOne(new LambdaQueryWrapper<UserChatHistory>()
                .select(UserChatHistory::getId)
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .orderByDesc(UserChatHistory::getId)
                .last("limit 1"));
        return row == null ? 0L : row.getId();
    }

    private Long maxFavorLogId(Long userWorldId) {
        UserCharacterFavorLog row = userCharacterFavorLogMapper.selectOne(
                new LambdaQueryWrapper<UserCharacterFavorLog>()
                        .select(UserCharacterFavorLog::getId)
                        .eq(UserCharacterFavorLog::getUserWorldId, userWorldId)
                        .orderByDesc(UserCharacterFavorLog::getId)
                        .last("limit 1"));
        return row == null ? 0L : row.getId();
    }

    private Long maxUserEventLogId(Long userWorldId) {
        UserEventLog row = userEventLogMapper.selectOne(new LambdaQueryWrapper<UserEventLog>()
                .select(UserEventLog::getId)
                .eq(UserEventLog::getUserWorldId, userWorldId)
                .orderByDesc(UserEventLog::getId)
                .last("limit 1"));
        return row == null ? 0L : row.getId();
    }

    private Long maxWorldEventLogId(Long userWorldId) {
        WorldEventLog row = worldEventLogMapper.selectOne(new LambdaQueryWrapper<WorldEventLog>()
                .select(WorldEventLog::getId)
                .eq(WorldEventLog::getUserWorldId, userWorldId)
                .orderByDesc(WorldEventLog::getId)
                .last("limit 1"));
        return row == null ? 0L : row.getId();
    }

    private Long maxGroupConversationId(Long userWorldId) {
        return Objects.requireNonNullElse(groupConversationMapper.selectMaxIdByUserWorldId(userWorldId), 0L);
    }

    private Long maxGroupMessageId(Long userWorldId) {
        return Objects.requireNonNullElse(groupChatMessageMapper.selectMaxIdByUserWorldId(userWorldId), 0L);
    }

    private Long maxGroupTurnId(Long userWorldId) {
        return Objects.requireNonNullElse(groupChatTurnMapper.selectMaxIdByUserWorldId(userWorldId), 0L);
    }

    private Long maxGroupReplyStepId(Long userWorldId) {
        return Objects.requireNonNullElse(groupChatReplyStepMapper.selectMaxIdByUserWorldId(userWorldId), 0L);
    }

    private Long maxGroupContextSummaryId(Long userWorldId) {
        return Objects.requireNonNullElse(groupContextSummaryMapper.selectMaxIdByUserWorldId(userWorldId), 0L);
    }

    private List<GroupConversationLockService.OwnedLock> lockActiveGroupConversations(Long userWorldId) {
        List<Long> conversationIds = groupConversationMapper.selectList(
                        new LambdaQueryWrapper<GroupConversation>()
                                .select(GroupConversation::getId)
                                .eq(GroupConversation::getUserWorldId, userWorldId)
                                .eq(GroupConversation::getStatus, GroupChatConstant.STATUS_ACTIVE)
                                .orderByAsc(GroupConversation::getId))
                .stream()
                .map(GroupConversation::getId)
                .toList();
        List<GroupConversationLockService.OwnedLock> locks = new ArrayList<>();
        try {
            for (Long conversationId : conversationIds) {
                GroupConversationLockService.OwnedLock lock = groupConversationLockService.tryLock(conversationId);
                if (lock == null) {
                    throw new UserRequestException("群聊正在生成回复，请稍后再存档或读档");
                }
                locks.add(lock);
            }
            return locks;
        } catch (RuntimeException e) {
            unlockGroupConversations(locks);
            throw e;
        }
    }

    private void unlockGroupConversations(List<GroupConversationLockService.OwnedLock> locks) {
        if (locks == null) {
            return;
        }
        for (int i = locks.size() - 1; i >= 0; i--) {
            groupConversationLockService.unlock(locks.get(i));
        }
    }

    private UserWorldSave getRequiredSave(Long userId, Long userWorldId) {
        UserWorldSave save = getUserWorldSave(userId, userWorldId);
        if (save == null) {
            throw new UserRequestException("当前世界没有存档");
        }
        return save;
    }

    private UserWorldSave getUserWorldSave(Long userId, Long userWorldId) {
        return userWorldSaveMapper.selectOne(new LambdaQueryWrapper<UserWorldSave>()
                .eq(UserWorldSave::getUserId, userId)
                .eq(UserWorldSave::getUserWorldId, userWorldId)
                .last("limit 1"));
    }

    private UserWorldSaveOverviewVO toOverview(UserWorldSave save) {
        return new UserWorldSaveOverviewVO()
                .setUserWorldId(save.getUserWorldId())
                .setSavedAt(save.getSavedAt())
                .setRemark(save.getRemark())
                .setCharacterFavors(save.getCharacterFavors());
    }

    private String normalizeRemark(UserWorldSaveCreateDTO createDTO) {
        if (createDTO == null || !StringUtils.hasText(createDTO.getRemark())) {
            return null;
        }
        return createDTO.getRemark().trim();
    }

    private void deleteHashFieldsByPattern(String hashKey, String pattern) {
        List<Object> fields = new ArrayList<>();
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash()
                .scan(hashKey, ScanOptions.scanOptions().match(pattern).count(RedisConstant.REDIS_SCAN_COUNT).build())) {
            cursor.forEachRemaining(entry -> fields.add(entry.getKey()));
        }
        if (!fields.isEmpty()) {
            redisTemplate.opsForHash().delete(hashKey, fields.toArray());
        }
    }

    private void deleteKeysByPattern(String pattern) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(RedisConstant.REDIS_SCAN_COUNT).build())) {
            cursor.forEachRemaining(keys::add);
        }
        deleteRedisKeys(keys);
    }

    private void deleteRedisKeys(Collection<String> keys) {
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private Long readLong(JSONObject jsonObject, String key) {
        if (!jsonObject.has(key) || jsonObject.isNull(key)) {
            return null;
        }
        return jsonObject.getLong(key);
    }

    private String topicBoundaryKey(Long userWorldId, Long characterId) {
        return RedisConstant.TOPIC_BOUNDARY_KEY_PREFIX + userWorldId + ":" + characterId;
    }

    private String normalizeType(String type) {
        return StringUtils.hasText(type) ? type : MessageType.USER.getValue();
    }

    private Long safeMax(Long value) {
        return value == null ? 0L : value;
    }

    private <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? List.of() : values;
    }
}
