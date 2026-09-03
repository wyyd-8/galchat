package com.me.galchat.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.GroupChatAgentDecision;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatToolCall;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.GroupTurnCheckpoint;
import com.me.galchat.domain.po.TrpgCombat;
import com.me.galchat.domain.po.TrpgRuntimeChildScene;
import com.me.galchat.domain.po.TrpgInvestigatorSuspension;
import com.me.galchat.domain.po.TrpgWeaponStash;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrpgSaveSnapshotDTO {

    private Integer formatVersion;
    private Long conversationId;
    private Long userWorldId;
    private Long worldId;
    private Long moduleId;
    private CursorSnapshot cursors;
    private ConversationStateSnapshot conversationState;
    private List<GroupReplyPlan> replyPlans;
    private List<GroupReplyPlanItem> replyPlanItems;
    private List<TrpgRuntimeChildScene> runtimeChildScenes;
    private List<TrpgInvestigatorSuspension> investigatorSuspensions;
    private List<CocCharacter> characters;
    private Map<Long, String> characterQuickNotes;
    private List<CocCharacterProfile> characterProfiles;
    private List<CocCharacterSkill> characterSkills;
    private List<CocCharacterWeapon> characterWeapons;
    private List<TrpgWeaponStash> weaponStash;
    private List<TrpgCombat> combats;
    private List<RestorableTurnSnapshot> restorableTurns;
    private GroupTurnCheckpoint checkpoint;
    private RedisStateSnapshot redisState;

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CursorSnapshot {
        private Long maxMessageId;
        private Long maxTurnId;
        private Long maxReplyStepId;
        private Long maxToolCallId;
        private Long maxAgentDecisionId;
        private Long maxContextSummaryId;
        private Long maxTopicId;
        private Long maxDiceSummaryId;
        private Long maxDiceResultId;
    }

    @Data
    @Accessors(chain = true)
    public static class ConversationStateSnapshot {
        private Long activeReplyPlanId;
        private String title;
        private String summary;
        private String status;
        private Integer version;
        private Integer gameDayNo;
        private String gameTimePeriod;
        private Integer gameTimeRevision;
        private Long gameTimeChangedStepId;
        private LocalDateTime gameTimeUpdatedAt;
        private LocalDateTime updatedAt;
        private LocalDateTime closedAt;
    }

    @Data
    @Accessors(chain = true)
    public static class RestorableTurnSnapshot {
        private GroupChatTurn turn;
        private List<GroupChatReplyStep> replySteps;
        private List<GroupChatMessage> messages;
        private List<GroupChatToolCall> toolCalls;
        private List<GroupChatAgentDecision> agentDecisions;
        private List<DiceRollSummary> diceSummaries;
        private List<DiceRollResult> diceResults;
    }

    @Data
    @Accessors(chain = true)
    public static class RedisStateSnapshot {
        private Set<Long> shownMaterialIds;
        private Long sceneSelectionTurnId;
        private Map<String, Long> sceneSelections;
        private Map<String, LocationOptionSnapshot> sceneOptions;
        private List<SceneProgressSnapshot> sceneProgress;
        private Boolean runFinishRequested;
    }

    @Data
    @Accessors(chain = true)
    public static class LocationOptionSnapshot {
        private Long locationId;
        private String name;
    }

    @Data
    @Accessors(chain = true)
    public static class SceneProgressSnapshot {
        private Long sceneId;
        private Set<String> readyActors;
        private Boolean finishRequested;
    }
}
