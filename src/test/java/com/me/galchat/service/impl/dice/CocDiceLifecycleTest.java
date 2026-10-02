package com.me.galchat.service.impl.dice;

import com.me.galchat.constant.*;
import com.me.galchat.domain.dto.*;
import com.me.galchat.domain.po.*;
import com.me.galchat.domain.vo.*;
import com.me.galchat.mapper.*;
import com.me.galchat.service.*;
import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.trpg.TrpgCombatLifecycleService;
import com.me.galchat.utils.DiceUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CocDiceLifecycleTest {
    final Map<Long, DiceRollSummary> summaries = new LinkedHashMap<>();
    final Map<Long, DiceRollResult> results = new LinkedHashMap<>();
    final DiceRollSummaryMapper summaryMapper = mock(DiceRollSummaryMapper.class);
    final DiceRollResultMapper resultMapper = mock(DiceRollResultMapper.class);
    final ICharacterCardService cards = mock(ICharacterCardService.class);
    final DiceFollowUpLocator locator = mock(DiceFollowUpLocator.class);
    final DiceMessageRoundAppender messages = mock(DiceMessageRoundAppender.class);
    final CocCharacter player = new CocCharacter().setId(11L).setRunId(7L).setName("林恩")
            .setActorType("PLAYER").setSanCurrent(70).setHpCurrent(5).setHpMax(10);
    CocDiceOrchestrationService service;

    @BeforeEach void setup() {
        when(summaryMapper.insert(any(DiceRollSummary.class))).thenAnswer(i -> {
            DiceRollSummary s = i.getArgument(0); s.setId(101L+summaries.size()); summaries.put(s.getId(),s); return 1;
        });
        when(summaryMapper.selectById(anyLong())).thenAnswer(i -> summaries.get(i.getArgument(0)));
        when(summaryMapper.selectByIdForUpdate(anyLong())).thenAnswer(i -> summaries.get(i.getArgument(0)));
        when(summaryMapper.selectOne(any())).thenAnswer(i -> summaries.isEmpty() ? null : summaries.values().stream().reduce((a,b)->b).orElseThrow());
        when(summaryMapper.updateById(any(DiceRollSummary.class))).thenReturn(1);
        when(resultMapper.insert(any(DiceRollResult.class))).thenAnswer(i -> {
            DiceRollResult r = i.getArgument(0); r.setId(201L+results.size()); results.put(r.getId(),r); return 1;
        });
        when(resultMapper.selectById(anyLong())).thenAnswer(i -> results.get(i.getArgument(0)));
        when(resultMapper.selectList(any())).thenAnswer(i -> {
            var w=i.<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DiceRollResult>>getArgument(0);
            w.getSqlSegment();
            return results.values().stream().filter(r -> w.getParamNameValuePairs().containsValue(r.getSummaryId())).toList();
        });
        when(resultMapper.updateById(any(DiceRollResult.class))).thenReturn(1);
        com.me.galchat.support.MybatisPlusTestSupport.initialize(DiceRollResult.class,DiceRollSummary.class);
        when(cards.requireDiceCharacter(7L,"林恩")).thenReturn(new CocDiceCharacterVO(11L,"PLAYER",null,"林恩",Map.of("侦查",70),5,10,70,70,60,0,false,false,false,false,false,null,null));
        when(cards.lockDiceCharacter(7L,11L)).thenReturn(player);
        var conversations=mock(GroupConversationService.class);
        when(conversations.requireActive(7L)).thenReturn(new GroupConversation().setId(7L).setStatus("active"));
        var random=mock(DiceRandomSource.class);
        when(random.d100()).thenReturn(37);
        service=new CocDiceOrchestrationService(new DiceRollInternalServiceImpl(summaryMapper,resultMapper),cards,conversations,locator,new CocDiceSummaryFormatter(),random,messages,mock(TrpgCombatLifecycleService.class));
    }
    KpDiceRequestDTOs.SanCheck san(String formula) {
        return JsonMapper.builder().disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build().readValue("{\"reason\":\"林恩目睹怪物\",\"characterNames\":[\"林恩\"],\"lossFormula\":\""+formula+"\"}",KpDiceRequestDTOs.SanCheck.class);
    }
    @ParameterizedTest @CsvSource({"1,69", "0,70", "2,68"})
    void unbranchedConstantDirectlyLosesSanWithoutCheck(String formula,int remaining) {
        var reply=service.requestSanCheck(7L,7L,san(formula));
        assertThat(player.getSanCurrent()).isEqualTo(remaining);
        assertThat(reply.summary().getRoundCount()).isEqualTo(1);
        assertThat(reply.results()).singleElement().satisfies(r->assertThat(r.getResolution().getType()).isEqualTo("SAN_LOSS"));
    }
    @ParameterizedTest @CsvSource({"1,1", "100,6", "40,1", "80,6"})
    void sanCheckAutomaticallyAppendsCorrectLossToSameSummary(int checkRoll,int expectedLoss) {
        var first=service.requestSanCheck(7L,7L,san("1/6"));
        var check=first.results().getFirst();
        try(var dice=mockStatic(DiceUtils.class,CALLS_REAL_METHODS)) {
            dice.when(()->DiceUtils.roll("1D100")).thenReturn(new DiceRollResultVO("1D100",List.of(),checkRoll));
            service.rollPlayerResult(check.getId());
        }
        assertThat(summaries).hasSize(1);
        assertThat(player.getSanCurrent()).isEqualTo(70-expectedLoss);
        assertThat(results.values()).anySatisfy(r->assertThat(r.getResolutionData().getType()).isEqualTo("SAN_LOSS"));
        assertThat(summaries.get(101L).getTotalResult()).contains("\n");
        verify(messages).appendRounds(eq(7L),eq(101L),any());
    }
    @ParameterizedTest @CsvSource({"1,1", "100,6"})
    void criticalSanUsesBranchExtremaWithoutRollingLoss(int checkRoll,int loss) {
        var first=service.requestSanCheck(7L,7L,san("1d3/1d6"));
        try(var dice=mockStatic(DiceUtils.class,CALLS_REAL_METHODS)) {
            dice.when(()->DiceUtils.roll("1D100")).thenReturn(new DiceRollResultVO("1D100",List.of(),checkRoll));
            service.rollPlayerResult(first.results().getFirst().getId());
        }
        assertThat(player.getSanCurrent()).isEqualTo(70-loss);
        assertThat(results.get(202L).getResultData().getFormula()).isEqualTo(Integer.toString(loss));
    }
    @Test void directDiceLossWaitsForPlayerAndDoesNotRunSanCheck() {
        var reply=service.requestSanCheck(7L,7L,san("1d6"));
        assertThat(reply.summary().getStatus()).isEqualTo("PENDING");
        assertThat(player.getSanCurrent()).isEqualTo(70);
        assertThat(reply.results()).singleElement().satisfies(r -> assertThat(r.getResolution().getType()).isEqualTo("SAN_LOSS"));
        try(var dice=mockStatic(DiceUtils.class,CALLS_REAL_METHODS)) {
            dice.when(()->DiceUtils.roll("1D6")).thenReturn(new DiceRollResultVO("1D6",List.of(),3));
            service.rollPlayerResult(reply.results().getFirst().getId());
            service.rollPlayerResult(reply.results().getFirst().getId());
        }
        assertThat(player.getSanCurrent()).isEqualTo(67);
        assertThat(results).hasSize(1);
    }
    @Test void ordinarySanSuccessStillRollsSuccessBranchAndDoesNotRepeatCheck() {
        var reply=service.requestSanCheck(7L,7L,san("1d3/1d6"));
        try(var dice=mockStatic(DiceUtils.class,CALLS_REAL_METHODS)) {
            dice.when(()->DiceUtils.roll("1D100")).thenReturn(new DiceRollResultVO("1D100",List.of(),40));
            service.rollPlayerResult(reply.results().getFirst().getId());
        }
        assertThat(player.getSanCurrent()).isEqualTo(70);
        assertThat(results.get(202L).getResultData().getFormula()).isEqualTo("1D3");
        assertThat(summaries.get(101L).getStatus()).isEqualTo("PENDING");
    }
    @Test void npcConstantLossImmediatelyResolvesMadnessInSameGroup() {
        player.setActorType("NPC");
        when(cards.requireDiceCharacter(7L,"林恩")).thenReturn(new CocDiceCharacterVO(11L,"NPC",null,"林恩",Map.of(),5,10,70,70,60,0,false,false,false,false,false,null,null));
        var reply=service.requestSanCheck(7L,7L,san("6"));
        assertThat(summaries).hasSize(1);
        assertThat(reply.summary().getRoundCount()).isEqualTo(2);
        assertThat(player.getSanCurrent()).isEqualTo(64);
        assertThat(player.getTemporaryInsanity()).isTrue();
        assertThat(reply.summary().getStatus()).isEqualTo("COMPLETED");
        assertThat(reply.results()).hasSize(3);
    }
    @Test void mixedSanGroupWaitsForAllChecksBeforeLossAndPlayerDiceBeforeMadness() {
        var npc = new CocCharacter().setId(12L).setRunId(7L).setName("守卫").setActorType("NPC").setSanCurrent(60);
        when(cards.requireDiceCharacter(7L,"守卫")).thenReturn(new CocDiceCharacterVO(12L,"NPC",null,"守卫",Map.of(),5,10,60,60,60,0,false,false,false,false,false,null,null));
        when(cards.lockDiceCharacter(7L,12L)).thenReturn(npc);
        KpDiceToolResult reply;
        try(var dice=mockStatic(DiceUtils.class,CALLS_REAL_METHODS)) {
            dice.when(()->DiceUtils.roll("1D100")).thenReturn(new DiceRollResultVO("1D100",List.of(),40));
            reply=service.requestSanCheck(7L,7L,new KpDiceRequestDTOs.SanCheck("两人目睹怪物",List.of("林恩","守卫"),"0/1D6"));
        }
        assertThat(results).hasSize(2);
        try(var dice=mockStatic(DiceUtils.class,CALLS_REAL_METHODS)) {
            dice.when(()->DiceUtils.roll("1D100")).thenReturn(new DiceRollResultVO("1D100",List.of(),80));
            service.rollPlayerResult(reply.results().getFirst().getId());
        }
        assertThat(results).hasSize(4);
        assertThat(npc.getSanCurrent()).isEqualTo(60);
        assertThat(player.getSanCurrent()).isEqualTo(70);
        try(var dice=mockStatic(DiceUtils.class,CALLS_REAL_METHODS)) {
            dice.when(()->DiceUtils.roll("1D6")).thenReturn(new DiceRollResultVO("1D6",List.of(),6));
            service.rollPlayerResult(203L);
            service.rollPlayerResult(203L);
        }
        assertThat(player.getSanCurrent()).isEqualTo(64);
        assertThat(results).hasSize(6);
        assertThat(summaries.get(101L).getRoundCount()).isEqualTo(3);
    }
    @Test void pushedSourceStillCannotBelongToAnotherConversation() {
        service.requestSanCheck(7L,7L,san("1"));
        summaries.get(101L).setConversationId(8L);
        assertThatThrownBy(()->service.requestPushedCheck(7L,7L,new KpDiceRequestDTOs.Pushed("再试",101L,null,null,List.of(new KpDiceRequestDTOs.CheckTarget("林恩","侦查",null)))))
                .hasMessageContaining("不属于当前群聊");
        assertThat(summaries).hasSize(1);
    }
    @Test void pushedCheckCreatesNewSummaryEvenWhenSourceIsNotOrdinaryOrFailed() {
        service.requestSanCheck(7L,7L,san("1"));
        String original=summaries.get(101L).getTotalResult();
        var pushed=service.requestPushedCheck(7L,7L,new KpDiceRequestDTOs.Pushed("林恩再尝试",101L,null,null,List.of(new KpDiceRequestDTOs.CheckTarget("林恩","侦查",null))));
        assertThat(pushed.summary().getId()).isEqualTo(102L);
        assertThat(pushed.results().getFirst().getRoundNo()).isEqualTo(1);
        assertThat(summaries.get(101L).getTotalResult()).isEqualTo(original);
    }
    @Test void olderPendingSummaryCannotBeRolledAfterNewGroupStarts() {
        var first=service.requestCheck(7L,7L,new KpDiceRequestDTOs.Check("林恩检查",null,new KpDiceRequestDTOs.CheckTarget("林恩","侦查",null)));
        service.requestCheck(7L,7L,new KpDiceRequestDTOs.Check("林恩再检查",null,new KpDiceRequestDTOs.CheckTarget("林恩","侦查",null)));
        assertThatThrownBy(()->service.rollPlayerResult(first.results().getFirst().getId())).hasMessageContaining("最后");
    }
    @ParameterizedTest
    @CsvSource({"opposed,false", "opposed,true", "damage,false", "damage,true"})
    void threeParticipantDiceStayInOneRoundAcrossTheirPlayerBundles(String kind, boolean mixedNpc) {
        List<String> names = List.of("林恩", "周晴", "守卫");
        List<CocCharacter> participants = new ArrayList<>();
        for (int index = 0; index < names.size(); index++) {
            long id = 11L + index;
            String actor = mixedNpc && index > 0 ? "NPC" : "PLAYER";
            String name = names.get(index);
            var card = new CocCharacter().setId(id).setRunId(7L).setName(name).setActorType(actor)
                    .setHpCurrent(20).setHpMax(20).setSanCurrent(70);
            participants.add(card);
            when(cards.requireDiceCharacter(7L, name)).thenReturn(new CocDiceCharacterVO(
                    id, actor, null, name, Map.of("侦查", 70), 20, 20, 70, 70, 60, 0,
                    false, false, false, false, false, null, null));
            when(cards.lockDiceCharacter(7L, id)).thenReturn(card);
        }
        KpDiceToolResult initial = kind.equals("opposed")
                ? service.requestOpposedCheck(7L, 7L, new KpDiceRequestDTOs.Opposed("三人对抗",
                    names.stream().map(name -> new KpDiceRequestDTOs.CheckTarget(name, "侦查", null)).toList(), null))
                : service.rollDamage(7L, 7L, new KpDiceRequestDTOs.Damage("三人受伤",
                    names.stream().map(name -> new KpDiceRequestDTOs.DamageTarget(name, "1D3")).toList()));
        assertThat(initial.results()).hasSize(3);
        var progress = service.rollPlayerResult(initial.results().getFirst().getId());
        if (kind.equals("damage") && !mixedNpc) {
            assertThat(progress.summary().getStatus()).isEqualTo("PENDING");
            assertThat(results.values().stream().filter(result -> result.getResolvedAt() == null)).hasSize(2);
            service.rollPlayerResult(initial.results().get(1).getId());
            progress = service.rollPlayerResult(initial.results().get(2).getId());
        }
        var retry = service.rollPlayerResult(initial.results().getFirst().getId());
        assertThat(progress.summary().getStatus()).isEqualTo("COMPLETED");
        assertThat(progress.summary().getRoundCount()).isEqualTo(1);
        assertThat(progress.createdResults()).isEmpty();
        assertThat(retry.createdResults()).isEmpty();
        assertThat(results.values()).hasSize(3).allSatisfy(result -> {
            assertThat(result.getRoundNo()).isEqualTo(1);
            assertThat(result.getResolvedAt()).isNotNull();
        });
        if (kind.equals("damage")) assertThat(participants).allSatisfy(card ->
                assertThat(card.getHpCurrent()).isBetween(17, 19));
    }

}
