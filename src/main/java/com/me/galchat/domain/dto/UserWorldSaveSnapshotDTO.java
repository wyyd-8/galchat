package com.me.galchat.domain.dto;

import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatThinkingHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.domain.po.WorldEventLog;
import com.me.galchat.domain.po.WorldStoryEvent;
import com.me.galchat.domain.po.WorldStoryEventCharacter;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class UserWorldSaveSnapshotDTO {

    private Integer formatVersion;

    private Long userWorldId;

    private Long worldId;

    private List<Long> characterIds;

    private Long maxChatHistoryId;

    private Long maxFavorLogId;

    private Long maxUserEventLogId;

    private Long maxWorldEventLogId;

    private Long maxStoryEventId;

    private Long maxStoryEventCharacterId;

    private Long maxGroupConversationId;

    private Long maxGroupMessageId;

    private Long maxGroupThinkingId;

    private Long maxGroupTurnId;

    private Long maxGroupReplyStepId;

    private Long maxGroupContextSummaryId;

    private List<CharacterStateSnapshot> characterStates;

    private List<TopicBoundarySnapshot> topicBoundaries;

    private List<CharacterChatRoundsSnapshot> recentChatRoundsByCharacter;

    private WorldEventLog lastWorldEventLog;

    private WorldStorySnapshot activeStory;

    @Data
    @Accessors(chain = true)
    public static class CharacterStateSnapshot {
        private Long characterId;
        private String characterName;
        private String characterImage;
        private LocalDateTime lastChatTime;
        private String lastChatContent;
        private Integer favorValue;
        private String userInfoPrompt;
    }

    @Data
    @Accessors(chain = true)
    public static class TopicBoundarySnapshot {
        private Long characterId;
        private Long previousStartId;
        private Long currentStartId;
        private Long lastCheckedMessageId;
        private Long activeStoryEventId;
        private Long activeStoryStartMessageId;
    }

    @Data
    @Accessors(chain = true)
    public static class CharacterChatRoundsSnapshot {
        private Long characterId;
        private List<ChatRoundSnapshot> rounds;
    }

    @Data
    @Accessors(chain = true)
    public static class ChatRoundSnapshot {
        private Long userMessageId;
        private List<UserChatHistory> historyRows;
        private List<UserChatThinkingHistory> thinkingRows;
        private List<UserChatToolCall> toolCallRows;
        private List<UserCharacterFavorLog> favorLogs;
    }

    @Data
    @Accessors(chain = true)
    public static class WorldStorySnapshot {
        private WorldStoryEvent storyEvent;
        private List<WorldStoryEventCharacter> characters;
    }
}
