package com.me.galchat.domain.dto;

import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatThinkingHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.domain.po.WorldEventLog;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatAgentDecision;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTopic;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.TrpgCombat;
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

    private Long maxGroupConversationId;

    private Long maxGroupMessageId;

    private Long maxGroupTurnId;

    private Long maxGroupReplyStepId;

    private Long maxGroupContextSummaryId;

    private Long maxGroupTopicId;

    private List<CharacterStateSnapshot> characterStates;

    private List<TopicBoundarySnapshot> topicBoundaries;

    private List<CharacterChatRoundsSnapshot> recentChatRoundsByCharacter;

    private List<GroupConversationTurnsSnapshot> recentGroupTurnsByConversation;

    private List<GroupConversationPlanSnapshot> conversationPlans;

    private List<TrpgCombat> combats;

    private WorldEventLog lastWorldEventLog;

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
        private List<Long> startIds;
        private Long lastCheckedMessageId;
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
        private Long anchorMessageId;
        private List<UserChatHistory> historyRows;
        private List<UserChatThinkingHistory> thinkingRows;
        private List<UserChatToolCall> toolCallRows;
        private List<UserCharacterFavorLog> favorLogs;
    }

    @Data
    @Accessors(chain = true)
    public static class GroupConversationTurnsSnapshot {
        private Long conversationId;
        private List<GroupTurnSnapshot> turns;
        private List<GroupChatTopic> topicRows;
    }

    @Data
    @Accessors(chain = true)
    public static class GroupTurnSnapshot {
        private GroupChatTurn turn;
        private List<GroupChatMessage> messages;
        private List<GroupChatReplyStep> replySteps;
        private List<GroupChatAgentDecision> decisions;
        private List<GroupChatToolCall> toolCalls;
        private List<UserCharacterFavorLog> favorLogs;
    }

    @Data
    @Accessors(chain = true)
    public static class GroupConversationPlanSnapshot {
        private Long conversationId;
        private ReplyPlanSnapshot activePlan;
    }

    @Data
    @Accessors(chain = true)
    public static class ReplyPlanSnapshot {
        private String source;
        private Long contextId;
        private List<ReplyPlanGroupSnapshot> groups;
        private ReplyPlanSnapshot resumePlan;
        private ReplyPlanSnapshot nextPlan;
    }

    @Data
    @Accessors(chain = true)
    public static class ReplyPlanGroupSnapshot {
        private String key;
        private String name;
        private Integer order;
        private List<ReplyPlanItemSnapshot> items;
    }

    @Data
    @Accessors(chain = true)
    public static class ReplyPlanItemSnapshot {
        private Integer order;
        private String actorType;
        private Long actorId;
        private Long subjectCharacterId;
    }
}
