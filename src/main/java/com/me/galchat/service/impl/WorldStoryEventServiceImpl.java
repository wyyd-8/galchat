package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.StoryConstant;
import com.me.galchat.domain.dto.WorldStoryEventAdvanceDTO;
import com.me.galchat.domain.dto.WorldStoryEventEndDTO;
import com.me.galchat.domain.dto.WorldStoryEventStartDTO;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldEventLog;
import com.me.galchat.domain.po.WorldStoryEvent;
import com.me.galchat.domain.po.WorldStoryEventCharacter;
import com.me.galchat.domain.vo.WorldStoryEventDetailVO;
import com.me.galchat.domain.vo.WorldStoryEventListVO;
import com.me.galchat.domain.vo.WorldStoryEventStartVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.WorldStoryEventCharacterMapper;
import com.me.galchat.mapper.WorldStoryEventMapper;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.IWorldEventLogService;
import com.me.galchat.service.IWorldStoryEventService;
import com.me.galchat.service.StoryOperationLockService;
import com.me.galchat.vector.WorldEventVectorService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorldStoryEventServiceImpl extends ServiceImpl<WorldStoryEventMapper, WorldStoryEvent>
        implements IWorldStoryEventService {

    private final IUserWorldPrefixService userWorldPrefixService;
    private final IUserCharacterInfoService userCharacterInfoService;
    private final WorldStoryEventCharacterMapper worldStoryEventCharacterMapper;
    private final UserChatHistoryMapper userChatHistoryMapper;
    private final TopicBoundaryService topicBoundaryService;
    private final IWorldEventLogService worldEventLogService;
    private final WorldEventVectorService worldEventVectorService;
    private final StoryOperationLockService storyOperationLockService;

    @Resource(name = "worldStoryOpeningClient")
    private ChatClient worldStoryOpeningClient;
    @Resource(name = "worldStoryAdvanceClient")
    private ChatClient worldStoryAdvanceClient;
    @Resource(name = "worldStoryEndClient")
    private ChatClient worldStoryEndClient;

    @Override
    public List<WorldStoryEventListVO> listStories(Long userWorldId) {
        userWorldPrefixService.checkUserWorldAuth(userWorldId, false);
        return lambdaQuery()
                .select(WorldStoryEvent::getId, WorldStoryEvent::getTitle)
                .eq(WorldStoryEvent::getUserWorldId, userWorldId)
                .orderByDesc(WorldStoryEvent::getId)
                .list()
                .stream()
                .map(storyEvent -> new WorldStoryEventListVO(storyEvent.getId(), storyEvent.getTitle()))
                .toList();
    }

    @Override
    public WorldStoryEventDetailVO getStoryDetail(Long storyEventId) {
        if (storyEventId == null) {
            throw new UserRequestException("故事事件id不能为空");
        }
        WorldStoryEvent storyEvent = lambdaQuery()
                .select(WorldStoryEvent::getId,
                        WorldStoryEvent::getUserWorldId,
                        WorldStoryEvent::getTitle,
                        WorldStoryEvent::getTheme,
                        WorldStoryEvent::getSummary,
                        WorldStoryEvent::getStatus,
                        WorldStoryEvent::getStartedAt,
                        WorldStoryEvent::getEndedAt)
                .eq(WorldStoryEvent::getId, storyEventId)
                .one();
        if (storyEvent == null) {
            throw new UserRequestException("故事事件不存在");
        }
        userWorldPrefixService.checkUserWorldAuth(storyEvent.getUserWorldId(), false);

        List<WorldStoryEventCharacter> characters = listStoryCharacters(storyEventId);
        return new WorldStoryEventDetailVO(
                storyEvent.getId(),
                storyEvent.getUserWorldId(),
                storyEvent.getTitle(),
                storyEvent.getTheme(),
                storyEvent.getSummary(),
                storyEvent.getStatus(),
                storyEvent.getStartedAt(),
                storyEvent.getEndedAt(),
                characterNames(storyEvent.getUserWorldId(), characters)
        );
    }

    @Override
    public void startStory(WorldStoryEventStartDTO startDTO) {
        checkStartRequest(startDTO);
        List<Long> characterIds = distinctCharacterIds(startDTO.getCharacterIds());
        List<RLock> locks = storyOperationLockService.lockStoryCharacters(startDTO.getUserWorldId(), characterIds);
        try {
            doStartStory(startDTO, characterIds);
        } finally {
            storyOperationLockService.unlockAll(locks);
        }
    }

    private void doStartStory(WorldStoryEventStartDTO startDTO, List<Long> characterIds) {
        UserWorldPrefix userWorld = userWorldPrefixService.checkUserWorldAuth(startDTO.getUserWorldId(), true);
        checkCharacters(startDTO.getUserWorldId(), characterIds);
        checkNoActiveStory(startDTO.getUserWorldId());

        StoryOpening opening = buildStoryOpening(startDTO, userWorld);
        LocalDateTime now = LocalDateTime.now();
        WorldStoryEvent storyEvent = new WorldStoryEvent()
                .setUserWorldId(startDTO.getUserWorldId())
                .setTitle(opening.title())
                .setTheme(startDTO.getTheme())
                .setCurrentScene(opening.currentScene())
                .setOpening(opening.opening())
                .setStatus(StoryConstant.ACTIVE_STATUS)
                .setStartedAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        save(storyEvent);

        characterIds.forEach(characterId -> createStoryCharacter(storyEvent, characterId));
    }

    @Override
    public WorldStoryEventStartVO getActiveStory(Long userWorldId) {
        if (userWorldId == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        userWorldPrefixService.checkUserWorldAuth(userWorldId, false);

        WorldStoryEvent storyEvent = lambdaQuery()
                .eq(WorldStoryEvent::getUserWorldId, userWorldId)
                .eq(WorldStoryEvent::getStatus, StoryConstant.ACTIVE_STATUS)
                .orderByDesc(WorldStoryEvent::getId)
                .last("limit 1")
                .one();
        if (storyEvent == null) {
            return null;
        }

        List<WorldStoryEventCharacter> characters = listStoryCharacters(storyEvent.getId());
        restoreStoryTopics(storyEvent, characters);
        return new WorldStoryEventStartVO(storyEvent, characters);
    }

    @Override
    public void advanceStory(Long storyEventId, WorldStoryEventAdvanceDTO advanceDTO) {
        checkAdvanceRequest(storyEventId, advanceDTO);
        WorldStoryEvent storyEvent = getActiveStoryById(storyEventId);
        List<WorldStoryEventCharacter> characters = listStoryCharacters(storyEventId);
        if (characters.isEmpty()) {
            throw new UserRequestException("故事参与角色为空");
        }
        List<RLock> locks = storyOperationLockService.lockStoryCharacters(storyEvent.getUserWorldId(),
                characterIds(characters));
        try {
            doAdvanceStory(storyEvent, advanceDTO, characters);
        } finally {
            storyOperationLockService.unlockAll(locks);
        }
    }

    private void doAdvanceStory(WorldStoryEvent storyEvent, WorldStoryEventAdvanceDTO advanceDTO,
                                List<WorldStoryEventCharacter> characters) {
        userWorldPrefixService.checkUserWorldAuth(storyEvent.getUserWorldId(), false);

        StoryProgress storyProgress = buildStoryProgress(storyEvent, advanceDTO.getTransition());
        updateStoryScene(storyEvent, storyProgress.currentScene());
        characters.forEach(character -> createStoryProgressMessage(storyEvent, character, storyProgress.progress()));
        restoreStoryTopics(storyEvent, characters);
    }

    @Override
    public void endStory(Long storyEventId, WorldStoryEventEndDTO endDTO) {
        if (storyEventId == null) {
            throw new UserRequestException("故事事件id不能为空");
        }

        WorldStoryEvent storyEvent = getActiveStoryById(storyEventId);
        List<WorldStoryEventCharacter> characters = listStoryCharacters(storyEventId);
        if (characters.isEmpty()) {
            throw new UserRequestException("故事参与角色为空");
        }
        List<RLock> locks = storyOperationLockService.lockStoryCharacters(storyEvent.getUserWorldId(),
                characterIds(characters));
        try {
            doEndStory(storyEvent, endDTO, characters);
        } finally {
            storyOperationLockService.unlockAll(locks);
        }
    }

    private void doEndStory(WorldStoryEvent storyEvent, WorldStoryEventEndDTO endDTO,
                            List<WorldStoryEventCharacter> characters) {
        userWorldPrefixService.checkUserWorldAuth(storyEvent.getUserWorldId(), false);

        List<UserChatHistory> storyHistories = listStoryHistories(storyEvent, characters);
        String summary = buildStorySummary(storyEvent, endDTO, storyHistories);
        LocalDateTime now = LocalDateTime.now();
        storyEvent.setSummary(summary)
                .setStatus(StoryConstant.CLOSED_STATUS)
                .setEndedAt(now)
                .setUpdatedAt(now);
        updateById(storyEvent);

        List<UserChatHistory> endMessages = characters.stream()
                .map(character -> createStoryEndMessage(storyEvent, character, summary))
                .toList();
        updateStoryCharacterEndMessages(characters, endMessages);
        createWorldEventLog(storyEvent, characters, summary, now);
        characters.forEach(character -> topicBoundaryService.endStoryTopic(storyEvent.getUserWorldId(),
                character.getCharacterId()));
    }

    private void checkStartRequest(WorldStoryEventStartDTO startDTO) {
        if (startDTO == null) {
            throw new UserRequestException("故事开始请求不能为空");
        }
        if (startDTO.getUserWorldId() == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        if (CollectionUtils.isEmpty(startDTO.getCharacterIds())) {
            throw new UserRequestException("参与角色不能为空");
        }
        if (!StringUtils.hasText(startDTO.getTheme()) && !StringUtils.hasText(startDTO.getOpening())) {
            throw new UserRequestException("故事主题或开场不能为空");
        }
    }

    private void checkAdvanceRequest(Long storyEventId, WorldStoryEventAdvanceDTO advanceDTO) {
        if (storyEventId == null) {
            throw new UserRequestException("故事事件id不能为空");
        }
        if (advanceDTO == null || !StringUtils.hasText(advanceDTO.getTransition())) {
            throw new UserRequestException("故事切换语句不能为空");
        }
    }

    private List<Long> distinctCharacterIds(List<Long> characterIds) {
        return characterIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private void checkCharacters(Long userWorldId, List<Long> characterIds) {
        if (characterIds.isEmpty()) {
            throw new UserRequestException("参与角色不能为空");
        }

        Set<Long> existingCharacterIds = new HashSet<>(userCharacterInfoService.listByUserWorldId(userWorldId)
                .stream()
                .map(UserCharacterInfo::getCharacterId)
                .toList());
        for (Long characterId : characterIds) {
            if (!existingCharacterIds.contains(characterId)) {
                throw new UserRequestException("参与角色不存在");
            }
        }
    }

    private void checkNoActiveStory(Long userWorldId) {
        Long count = lambdaQuery()
                .eq(WorldStoryEvent::getUserWorldId, userWorldId)
                .eq(WorldStoryEvent::getStatus, StoryConstant.ACTIVE_STATUS)
                .count();
        if (count != null && count > 0) {
            throw new UserRequestException("当前世界已有进行中的故事");
        }
    }

    private WorldStoryEvent getActiveStoryById(Long storyEventId) {
        WorldStoryEvent storyEvent = getById(storyEventId);
        if (storyEvent == null) {
            throw new UserRequestException("故事事件不存在");
        }
        if (!StoryConstant.ACTIVE_STATUS.equals(storyEvent.getStatus())) {
            throw new UserRequestException("故事事件未处于进行中");
        }
        return storyEvent;
    }

    private StoryOpening buildStoryOpening(WorldStoryEventStartDTO startDTO, UserWorldPrefix userWorld) {
        if (StringUtils.hasText(startDTO.getTitle())
                && StringUtils.hasText(startDTO.getCurrentScene())
                && StringUtils.hasText(startDTO.getOpening())) {
            return new StoryOpening(startDTO.getTitle().trim(), startDTO.getCurrentScene().trim(),
                    startDTO.getOpening().trim());
        }

        StoryOpening generatedOpening = generateStoryOpening(startDTO, userWorld);
        String title = StringUtils.hasText(startDTO.getTitle()) ? startDTO.getTitle().trim() : generatedOpening.title();
        String currentScene = StringUtils.hasText(startDTO.getCurrentScene())
                ? startDTO.getCurrentScene().trim()
                : generatedOpening.currentScene();
        String opening = StringUtils.hasText(startDTO.getOpening())
                ? startDTO.getOpening().trim()
                : generatedOpening.opening();
        if (!StringUtils.hasText(title) || !StringUtils.hasText(currentScene) || !StringUtils.hasText(opening)) {
            throw new UserRequestException("故事开场生成失败");
        }
        return new StoryOpening(title, currentScene, opening);
    }

    private StoryOpening generateStoryOpening(WorldStoryEventStartDTO startDTO, UserWorldPrefix userWorld) {
        String content = worldStoryOpeningClient.prompt()
                .user(formatOpeningPrompt(startDTO, userWorld))
                .call()
                .content();
        try {
            JSONObject jsonObject = new JSONObject(normalizeJson(content));
            return new StoryOpening(
                    jsonObject.optString("title", ""),
                    jsonObject.optString("currentScene", ""),
                    jsonObject.optString("opening", "")
            );
        } catch (JSONException e) {
            log.warn("故事开场生成结果不是有效JSON: {}", content);
            throw new UserRequestException("故事开场生成失败");
        }
    }

    private StoryProgress buildStoryProgress(WorldStoryEvent storyEvent, String transition) {
        String content = worldStoryAdvanceClient.prompt()
                .user(formatAdvancePrompt(storyEvent, transition))
                .call()
                .content();
        try {
            JSONObject jsonObject = new JSONObject(normalizeJson(content));
            String currentScene = jsonObject.optString("currentScene", storyEvent.getCurrentScene());
            String progress = jsonObject.optString("progress", "");
            if (!StringUtils.hasText(progress)) {
                progress = transition.trim();
            }
            if (!StringUtils.hasText(currentScene)) {
                currentScene = storyEvent.getCurrentScene();
            }
            return new StoryProgress(currentScene.trim(), progress.trim());
        } catch (JSONException e) {
            log.warn("故事推进生成结果不是有效JSON: {}", content);
            return new StoryProgress(storyEvent.getCurrentScene(), transition.trim());
        }
    }

    private String buildStorySummary(WorldStoryEvent storyEvent, WorldStoryEventEndDTO endDTO,
                                     List<UserChatHistory> storyHistories) {
        String content = worldStoryEndClient.prompt()
                .user(formatEndPrompt(storyEvent, endDTO, storyHistories))
                .call()
                .content();
        try {
            JSONObject jsonObject = new JSONObject(normalizeJson(content));
            String summary = jsonObject.optString("summary", "");
            if (StringUtils.hasText(summary)) {
                return summary.trim();
            }
        } catch (JSONException e) {
            log.warn("故事总结生成结果不是有效JSON: {}", content);
        }
        return fallbackSummary(storyEvent, endDTO);
    }

    private String formatAdvancePrompt(WorldStoryEvent storyEvent, String transition) {
        StringBuilder builder = new StringBuilder();
        appendPromptLine(builder, "标题", storyEvent.getTitle());
        appendPromptLine(builder, "主题", storyEvent.getTheme());
        appendPromptLine(builder, "当前场景", storyEvent.getCurrentScene());
        appendPromptLine(builder, "故事开场", storyEvent.getOpening());
        appendPromptLine(builder, "切换语句", transition);
        return builder.toString();
    }

    private String formatEndPrompt(WorldStoryEvent storyEvent, WorldStoryEventEndDTO endDTO,
                                   List<UserChatHistory> storyHistories) {
        StringBuilder builder = new StringBuilder();
        appendPromptLine(builder, "标题", storyEvent.getTitle());
        appendPromptLine(builder, "主题", storyEvent.getTheme());
        appendPromptLine(builder, "当前场景", storyEvent.getCurrentScene());
        appendPromptLine(builder, "故事开场", storyEvent.getOpening());
        if (endDTO != null) {
            appendPromptLine(builder, "用户离开说明", endDTO.getEnding());
        }
        builder.append("故事消息：\n");
        for (UserChatHistory history : storyHistories) {
            builder.append(formatHistory(history)).append('\n');
        }
        return builder.toString();
    }

    private String formatOpeningPrompt(WorldStoryEventStartDTO startDTO, UserWorldPrefix userWorld) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户世界：").append(userWorld.getName()).append('\n');
        appendPromptLine(builder, "世界背景", userWorldPrefixService.buildWorldPrompt(userWorld.getWorldId()));
        appendPromptLine(builder, "故事主题", startDTO.getTheme());
        appendPromptLine(builder, "用户指定标题", startDTO.getTitle());
        appendPromptLine(builder, "用户指定场景", startDTO.getCurrentScene());
        appendPromptLine(builder, "用户指定开场", startDTO.getOpening());
        return builder.toString();
    }

    private void appendPromptLine(StringBuilder builder, String key, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(key).append("：").append(value.trim()).append('\n');
        }
    }

    private String normalizeJson(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String trimmedContent = content.trim();
        int beginIndex = trimmedContent.indexOf('{');
        int endIndex = trimmedContent.lastIndexOf('}');
        if (beginIndex < 0 || endIndex < beginIndex) {
            return trimmedContent;
        }
        return trimmedContent.substring(beginIndex, endIndex + 1);
    }

    private void createStoryCharacter(WorldStoryEvent storyEvent, Long characterId) {
        UserChatHistory startMessage = new UserChatHistory()
                .setUserWorldId(storyEvent.getUserWorldId())
                .setCharacterId(characterId)
                .setType(ChatConstant.STORY_START_TYPE)
                .setContent(formatStoryStartContent(storyEvent))
                .setTimestamp(LocalDateTime.now());
        userChatHistoryMapper.insert(startMessage);

        WorldStoryEventCharacter storyCharacter = new WorldStoryEventCharacter()
                .setStoryEventId(storyEvent.getId())
                .setCharacterId(characterId)
                .setStartMessageId(startMessage.getId());
        worldStoryEventCharacterMapper.insert(storyCharacter);
        topicBoundaryService.startStoryTopic(storyEvent.getUserWorldId(), characterId,
                storyEvent.getId(), startMessage.getId());
    }

    private UserChatHistory createStoryEndMessage(WorldStoryEvent storyEvent,
                                                  WorldStoryEventCharacter character,
                                                  String summary) {
        UserChatHistory endMessage = new UserChatHistory()
                .setUserWorldId(storyEvent.getUserWorldId())
                .setCharacterId(character.getCharacterId())
                .setType(ChatConstant.STORY_END_TYPE)
                .setContent(formatStoryEndContent(storyEvent, summary))
                .setTimestamp(LocalDateTime.now());
        userChatHistoryMapper.insert(endMessage);
        return endMessage;
    }

    private void createStoryProgressMessage(WorldStoryEvent storyEvent,
                                            WorldStoryEventCharacter character,
                                            String progress) {
        UserChatHistory progressMessage = new UserChatHistory()
                .setUserWorldId(storyEvent.getUserWorldId())
                .setCharacterId(character.getCharacterId())
                .setType(ChatConstant.STORY_PROGRESS_TYPE)
                .setContent(formatStoryProgressContent(storyEvent, progress))
                .setTimestamp(LocalDateTime.now());
        userChatHistoryMapper.insert(progressMessage);
    }

    private void updateStoryScene(WorldStoryEvent storyEvent, String currentScene) {
        String newScene = StringUtils.hasText(currentScene) ? currentScene.trim() : storyEvent.getCurrentScene();
        storyEvent.setCurrentScene(newScene)
                .setUpdatedAt(LocalDateTime.now());
        updateById(storyEvent);
    }

    private List<WorldStoryEventCharacter> listStoryCharacters(Long storyEventId) {
        return worldStoryEventCharacterMapper.selectList(new LambdaQueryWrapper<WorldStoryEventCharacter>()
                .eq(WorldStoryEventCharacter::getStoryEventId, storyEventId)
                .orderByAsc(WorldStoryEventCharacter::getId));
    }

    private List<Long> characterIds(List<WorldStoryEventCharacter> characters) {
        return characters.stream()
                .map(WorldStoryEventCharacter::getCharacterId)
                .toList();
    }

    private List<String> characterNames(Long userWorldId, List<WorldStoryEventCharacter> characters) {
        if (characters.isEmpty()) {
            return List.of();
        }

        Set<Long> characterIds = new HashSet<>(characters.stream()
                .map(WorldStoryEventCharacter::getCharacterId)
                .toList());
        Map<Long, String> nameByCharacterId = new HashMap<>();
        for (UserCharacterInfo character : userCharacterInfoService.listByUserWorldId(userWorldId)) {
            if (characterIds.contains(character.getCharacterId())) {
                nameByCharacterId.putIfAbsent(character.getCharacterId(), character.getCharacterName());
            }
        }
        return characters.stream()
                .map(character -> nameByCharacterId.get(character.getCharacterId()))
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<UserChatHistory> listStoryHistories(WorldStoryEvent storyEvent,
                                                     List<WorldStoryEventCharacter> characters) {
        List<UserChatHistory> histories = new ArrayList<>();
        for (WorldStoryEventCharacter character : characters) {
            if (character.getStartMessageId() == null) {
                continue;
            }
            histories.addAll(userChatHistoryMapper.selectList(new LambdaQueryWrapper<UserChatHistory>()
                    .eq(UserChatHistory::getUserWorldId, storyEvent.getUserWorldId())
                    .eq(UserChatHistory::getCharacterId, character.getCharacterId())
                    .ge(UserChatHistory::getId, character.getStartMessageId())
                    .orderByAsc(UserChatHistory::getId)));
        }
        histories.sort((left, right) -> {
            LocalDateTime leftTime = left.getTimestamp();
            LocalDateTime rightTime = right.getTimestamp();
            if (leftTime != null && rightTime != null && !leftTime.equals(rightTime)) {
                return leftTime.compareTo(rightTime);
            }
            return Long.compare(left.getId() == null ? 0L : left.getId(), right.getId() == null ? 0L : right.getId());
        });
        return histories;
    }

    private void updateStoryCharacterEndMessages(List<WorldStoryEventCharacter> characters,
                                                 List<UserChatHistory> endMessages) {
        for (int i = 0; i < characters.size(); i++) {
            WorldStoryEventCharacter character = characters.get(i);
            UserChatHistory endMessage = endMessages.get(i);
            worldStoryEventCharacterMapper.update(new WorldStoryEventCharacter().setEndMessageId(endMessage.getId()),
                    new LambdaUpdateWrapper<WorldStoryEventCharacter>()
                            .eq(WorldStoryEventCharacter::getId, character.getId()));
            character.setEndMessageId(endMessage.getId());
        }
    }

    private void createWorldEventLog(WorldStoryEvent storyEvent,
                                     List<WorldStoryEventCharacter> characters,
                                     String summary,
                                     LocalDateTime timestamp) {
        Long[] visibleCharacters = characters.stream()
                .map(WorldStoryEventCharacter::getCharacterId)
                .toArray(Long[]::new);
        WorldEventLog worldEventLog = new WorldEventLog()
                .setUserWorldId(storyEvent.getUserWorldId())
                .setTitle(storyEvent.getTitle())
                .setStoryEventId(storyEvent.getId())
                .setEventDescription(summary)
                .setVisibleCharacters(visibleCharacters)
                .setTimestamp(timestamp);
        worldEventLogService.save(worldEventLog);
        worldEventVectorService.addWorldEventLog(worldEventLog);
    }

    private void restoreStoryTopics(WorldStoryEvent storyEvent, List<WorldStoryEventCharacter> characters) {
        for (WorldStoryEventCharacter character : characters) {
            topicBoundaryService.startStoryTopic(storyEvent.getUserWorldId(), character.getCharacterId(),
                    storyEvent.getId(), character.getStartMessageId());
        }
    }

    private String formatStoryStartContent(WorldStoryEvent storyEvent) {
        StringBuilder builder = new StringBuilder();
        builder.append("【故事开始】\n");
        appendPromptLine(builder, "标题", storyEvent.getTitle());
        appendPromptLine(builder, "主题", storyEvent.getTheme());
        appendPromptLine(builder, "当前场景", storyEvent.getCurrentScene());
        appendPromptLine(builder, "开场", storyEvent.getOpening());
        return builder.toString().trim();
    }

    private String formatStoryProgressContent(WorldStoryEvent storyEvent, String progress) {
        StringBuilder builder = new StringBuilder();
        builder.append("【故事推进】\n");
        appendPromptLine(builder, "标题", storyEvent.getTitle());
        appendPromptLine(builder, "当前场景", storyEvent.getCurrentScene());
        appendPromptLine(builder, "推进", progress);
        return builder.toString().trim();
    }

    private String formatStoryEndContent(WorldStoryEvent storyEvent, String summary) {
        StringBuilder builder = new StringBuilder();
        builder.append("【故事结束】\n");
        appendPromptLine(builder, "标题", storyEvent.getTitle());
        appendPromptLine(builder, "总结", summary);
        return builder.toString().trim();
    }

    private String formatHistory(UserChatHistory history) {
        return "[" + (history.getTimestamp() == null ? "unknown" : history.getTimestamp()) + "] "
                + "characterId=" + history.getCharacterId() + " "
                + history.getType() + ": " + history.getContent();
    }

    private String fallbackSummary(WorldStoryEvent storyEvent, WorldStoryEventEndDTO endDTO) {
        StringBuilder builder = new StringBuilder();
        appendPromptLine(builder, "标题", storyEvent.getTitle());
        appendPromptLine(builder, "主题", storyEvent.getTheme());
        appendPromptLine(builder, "当前场景", storyEvent.getCurrentScene());
        appendPromptLine(builder, "开场", storyEvent.getOpening());
        if (endDTO != null) {
            appendPromptLine(builder, "结尾", endDTO.getEnding());
        }
        return builder.toString().trim();
    }

    private record StoryOpening(String title, String currentScene, String opening) {
    }

    private record StoryProgress(String currentScene, String progress) {
    }
}
