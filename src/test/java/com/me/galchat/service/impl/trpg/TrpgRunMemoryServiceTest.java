package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.domain.vo.DiceResolutionDataVO;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.groupchat.dice.DiceRollMessageContent;
import com.me.galchat.groupchat.dice.GroupDiceMessageFormatter;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.GroupChatMemberMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupContextSummaryMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.vector.TrpgTurnVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class TrpgRunMemoryServiceTest {

    @Test
    void recentContextContainsOnlyBriefCurrentSnapshotAndRefreshesAfterRestore() {
        when(conversationMapper.selectRecentTrpgByCharacter(5L, 9L)).thenReturn(List.of(
                new GroupConversation()
                        .setId(71L).setModuleId(4L)
                        .setStatus("closed").setTitle("不应进入上下文的标题")
                        .setUpdatedAt(LocalDateTime.of(2026, 9, 12, 10, 0))));
        when(characterMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new CocCharacter().setRunId(71L).setActorType("PLAYER").setName("林恩"),
                new CocCharacter().setRunId(71L).setActorType("BOT")
                        .setPlayerName("艾琳").setName("威尔").setQuickNotes("KP秘密")));

        when(moduleMapper.selectBatchIds(List.of(4L))).thenReturn(List.of(
                new CocModule().setId(4L).setName("雾中车站")));
        String prompt = service.formatRecentContext(5L, 9L);

        assertThat(prompt).contains("71", "雾中车站", "closed", "2026-09-12T10:00",
                "用户", "林恩", "艾琳", "威尔", "\"updatedAt\"");
        assertThat(prompt).doesNotContain("不应进入上下文的标题", "KP秘密");
        when(conversationMapper.selectRecentTrpgByCharacter(5L, 9L)).thenReturn(List.of());
        assertThat(service.formatRecentContext(5L, 9L)).isEmpty();
        assertThat(service.formatRecentContext(6L, 9L)).isEmpty();
    }

    private GroupConversationMapper conversationMapper;
    private GroupChatMemberMapper memberMapper;
    private CocModuleMapper moduleMapper;
    private CocCharacterMapper characterMapper;
    private ICharacterCardService cardService;
    private GroupReplyPlanMapper planMapper;
    private GroupContextSummaryMapper summaryMapper;
    private GroupChatMessageMapper messageMapper;
    private GroupChatTurnMapper turnMapper;
    private DiceRollMessageCodec diceCodec;
    private DiceRollResultMapper diceResultMapper;
    private GroupDiceMessageFormatter diceFormatter;
    private TrpgTurnVectorService vectorService;
    private TrpgRunMemoryService service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(GroupConversationMapper.class);
        memberMapper = mock(GroupChatMemberMapper.class);
        moduleMapper = mock(CocModuleMapper.class);
        characterMapper = mock(CocCharacterMapper.class);
        cardService = mock(ICharacterCardService.class);
        planMapper = mock(GroupReplyPlanMapper.class);
        summaryMapper = mock(GroupContextSummaryMapper.class);
        messageMapper = mock(GroupChatMessageMapper.class);
        turnMapper = mock(GroupChatTurnMapper.class);
        diceCodec = mock(DiceRollMessageCodec.class);
        diceResultMapper = mock(DiceRollResultMapper.class);
        diceFormatter = mock(GroupDiceMessageFormatter.class);
        vectorService = mock(TrpgTurnVectorService.class);
        service = new TrpgRunMemoryService(
                conversationMapper, memberMapper, moduleMapper,
                characterMapper, cardService, planMapper, summaryMapper,
                messageMapper, turnMapper, diceCodec, diceResultMapper,
                diceFormatter, vectorService);
    }

    @Test
    void listReturnsOnlyParticipatedTrpgRunsInTheCurrentWorldWithRoleNames() {
        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new GroupChatMember().setConversationId(71L)
                        .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                        .setActorId(9L)));
        when(conversationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new GroupConversation().setId(71L).setUserWorldId(5L)
                        .setModuleId(4L).setMode(GroupChatConstant.MODE_TRPG)
                        .setStatus(GroupChatConstant.STATUS_ACTIVE)));
        when(moduleMapper.selectBatchIds(List.of(4L))).thenReturn(List.of(
                new CocModule().setId(4L).setName("雾中车站")));
        when(characterMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new CocCharacter().setId(101L).setRunId(71L)
                        .setActorType("PLAYER").setPlayerName("account_123")
                        .setName("林恩"),
                new CocCharacter().setId(102L).setRunId(71L)
                        .setActorType("BOT").setParticipantId(9L)
                        .setPlayerName("艾琳").setName("威尔")));

        var result = service.listRuns(5L, 9L);

        assertThat(result.runs()).singleElement().satisfies(run -> {
            assertThat(run.runId()).isEqualTo(71L);
            assertThat(run.moduleName()).isEqualTo("雾中车站");
            assertThat(run.names()).containsExactlyEntriesOf(
                    new LinkedHashMap<>(Map.of(
                            "用户", "林恩", "艾琳", "威尔")));
        });
    }

    @Test
    void detailsExposeOnlyTheControlledBriefCardAndPublicOwnCheckGrades() {
        GroupConversation run = new GroupConversation().setId(71L)
                .setUserWorldId(5L).setModuleId(4L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
        allow(run);
        CocCharacter card = new CocCharacter().setId(102L).setRunId(71L)
                .setActorType("BOT").setParticipantId(9L)
                .setName("威尔").setOccupation("记者")
                .setStr(40).setHpCurrent(8).setHpMax(10);
        when(characterMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(card));
        when(cardService.getById(102L)).thenReturn(new CharacterCardVO(
                card, List.of(
                new CocCharacterSkill().setDisplayName("侦查")
                        .setBaseValue(25).setValue(65),
                new CocCharacterSkill().setDisplayName("聆听")
                        .setBaseValue(20).setValue(20)),
                List.of(), null));
        when(moduleMapper.selectById(4L)).thenReturn(
                new CocModule().setId(4L).setName("雾中车站"));
        GroupChatMessage dice = new GroupChatMessage().setId(301L)
                .setMessageKind(GroupChatConstant.MESSAGE_DICE_ROLL)
                .setContent("public-reference")
                .setVisibility("public")
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
        when(messageMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(dice));
        when(diceCodec.decode("public-reference"))
                .thenReturn(new DiceRollMessageContent(401L, List.of(1, 2)));
        when(diceResultMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                result(401L, 1, 102L, "SUCCESS"),
                result(401L, 2, 102L, "FUMBLE"),
                result(999L, 1, 102L, "CRITICAL_SUCCESS"),
                result(401L, 2, 777L, "CRITICAL_SUCCESS")));

        var result = service.getRunDetails(5L, 9L, 71L);

        assertThat(result.controlledInvestigator().name()).isEqualTo("威尔");
        assertThat(result.controlledInvestigator().nonBaseSkills())
                .containsExactly(Map.entry("侦查", 65));
        assertThat(result.ownDiceStatistics().total()).isEqualTo(2);
        assertThat(result.ownDiceStatistics().success()).isEqualTo(1);
        assertThat(result.ownDiceStatistics().fumble()).isEqualTo(1);
        assertThat(result.ownDiceStatistics().criticalSuccess()).isZero();
    }

    @Test
    void closedDetailsReturnSummaryAndEndTimeButNoLatestPublicMessage() {
        LocalDateTime closedAt = LocalDateTime.of(2026, 9, 1, 12, 0);
        GroupConversation run = new GroupConversation().setId(71L)
                .setUserWorldId(5L).setModuleId(4L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_CLOSED)
                .setClosedAt(closedAt).setSummary("众人离开车站");
        allow(run);
        CocCharacter card = new CocCharacter().setId(102L).setRunId(71L)
                .setActorType("BOT").setParticipantId(9L).setName("威尔");
        when(characterMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(card));
        when(cardService.getById(102L)).thenReturn(
                new CharacterCardVO(card, List.of(), List.of(), null));
        when(moduleMapper.selectById(4L)).thenReturn(new CocModule().setName("模组"));
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        var result = service.getRunDetails(5L, 9L, 71L);

        assertThat(result.closedAt()).isEqualTo(closedAt);
        assertThat(result.summary()).isEqualTo("众人离开车站");
        assertThat(result.latestPublicMessage()).isNull();
    }

    @Test
    void rejectsARunThatTheCurrentRoleDidNotParticipateIn() {
        when(conversationMapper.selectById(71L)).thenReturn(
                new GroupConversation().setId(71L).setUserWorldId(5L)
                        .setMode(GroupChatConstant.MODE_TRPG));
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> service.getRunDetails(5L, 9L, 71L))
                .hasMessage("跑团不存在或当前角色未参与");
    }

    @Test
    void searchRefetchesCurrentCompletedRoundsAndDropsStaleVectorHits() {
        GroupConversation run = new GroupConversation().setId(71L)
                .setUserWorldId(5L).setMode(GroupChatConstant.MODE_TRPG);
        allow(run);
        LocalDateTime occurredAt = LocalDateTime.of(2026, 9, 1, 9, 0);
        when(vectorService.search(71L, "仓库")).thenReturn(List.of(
                new TrpgTurnVectorService.Candidate(
                        11L, occurredAt, 0.95, 0.95),
                new TrpgTurnVectorService.Candidate(
                        12L, occurredAt, 0.90, 0.90),
                new TrpgTurnVectorService.Candidate(
                        13L, occurredAt, 0.85, 0.85)));
        when(turnMapper.selectById(11L)).thenReturn(null);
        when(turnMapper.selectById(12L)).thenReturn(
                new com.me.galchat.domain.po.GroupChatTurn()
                        .setId(12L).setConversationId(99L)
                        .setStatus(GroupChatConstant.STATUS_COMPLETED));
        when(turnMapper.selectById(13L)).thenReturn(
                new com.me.galchat.domain.po.GroupChatTurn()
                        .setId(13L).setConversationId(71L)
                        .setStatus(GroupChatConstant.STATUS_COMPLETED));
        when(characterMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of());
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new GroupChatMessage().setId(301L).setTurnId(13L)
                        .setMessageKind(GroupChatConstant.MESSAGE_NARRATION)
                        .setVisibility("public")
                        .setStatus(GroupChatConstant.STATUS_COMPLETED)
                        .setContent("众人在仓库找到了账册")));

        var result = service.searchChatRounds(5L, 9L, 71L, " 仓库 ");

        assertThat(result.rounds()).singleElement().satisfies(round -> {
            assertThat(round.turnId()).isEqualTo(13L);
            assertThat(round.messages()).singleElement()
                    .extracting(message -> message.content())
                    .isEqualTo("众人在仓库找到了账册");
        });
    }

    private void allow(GroupConversation run) {
        when(conversationMapper.selectById(71L)).thenReturn(run);
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
    }

    private DiceRollResult result(
            Long summaryId, int roundNo, Long cardId, String category) {
        return new DiceRollResult().setSummaryId(summaryId)
                .setRoundNo(roundNo).setResolvedAt(LocalDateTime.now())
                .setResolutionData(new DiceResolutionDataVO()
                        .setRule(new LinkedHashMap<>(Map.of("cardId", cardId)))
                        .setOutcome(new LinkedHashMap<>(Map.of(
                                "category", category))));
    }
}
