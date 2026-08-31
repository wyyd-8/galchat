package com.me.galchat.service.impl;

import com.me.galchat.constant.CocCheckDifficulty;
import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.HealingSourceMode;
import com.me.galchat.constant.HealingMode;
import com.me.galchat.constant.FirearmDistance;
import com.me.galchat.constant.GroupCheckRule;
import com.me.galchat.domain.dto.DiceRollResultCreateDTO;
import com.me.galchat.domain.dto.KpDiceRequestDTOs;
import com.me.galchat.domain.dto.KpFirearmRequestDTOs;
import com.me.galchat.domain.dto.KpMeleeRequestDTOs;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.DiceRollAggregate;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.service.DiceFollowUpLocator;
import com.me.galchat.service.DiceMessageRoundAppender;
import com.me.galchat.service.DiceRandomSource;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.IDiceRollInternalService;
import com.me.galchat.utils.DiceUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.me.galchat.constant.FirearmFiringMode;
import com.me.galchat.constant.MeleeDefenseMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CocDiceOrchestrationServiceTest {

    private IDiceRollInternalService internal;
    private ICharacterCardService cards;
    private GroupConversationService conversations;
    private DiceFollowUpLocator followUps;
    private DiceRandomSource randomSource;
    private DiceMessageRoundAppender messageRoundAppender;
    private TrpgCombatLifecycleService combatLifecycleService;
    private CocDiceOrchestrationService service;

    @BeforeEach
    void setUp() {
        internal = mock(IDiceRollInternalService.class);
        cards = mock(ICharacterCardService.class);
        conversations = mock(GroupConversationService.class);
        followUps = mock(DiceFollowUpLocator.class);
        randomSource = mock(DiceRandomSource.class);
        messageRoundAppender = mock(DiceMessageRoundAppender.class);
        combatLifecycleService = mock(TrpgCombatLifecycleService.class);
        service = new CocDiceOrchestrationService(
                internal,
                cards,
                conversations,
                followUps,
                new CocDiceSummaryFormatter(),
                randomSource,
                messageRoundAppender,
                combatLifecycleService);
        when(conversations.requireActive(7L))
                .thenReturn(new GroupConversation().setId(7L).setStatus("active"));
    }

    @Test
    void agentResultIsVisibleWhilePlayerResultIsPending() {
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(player("林恩", 70));
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(agent(12L, "陈默", 45));
        stubCreate(7L);

        KpDiceToolResult result = service.requestGroupCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.GroupCheck(
                        "调查书房",
                        CocCheckDifficulty.REGULAR,
                        null,
                        List.of(
                                target("林恩", "侦查"),
                                target("陈默", "侦查"))));

        assertThat(result.results()).hasSize(2);
        assertThat(result.results().get(0).getResultData().getResult()).isNull();
        assertThat(result.results().get(0).getResultData().getModules()).isNotEmpty();
        assertThat(result.results().get(1).getResolution().getOutcome())
                .containsEntry("category", "SUCCESS")
                .containsEntry("rank", "REGULAR");
        assertThat(result.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_PENDING);
        assertThat(result.semanticResult())
                .isEqualTo("陈默进行“侦查”检定：成功");
        assertThat(result.results())
                .allSatisfy(detail -> assertThat(detail.getResolution())
                        .hasFieldOrPropertyWithValue("groupRule", "SEPARATE"));
    }

    @Test
    void checkRuleSnapshotUsesCardValueAndStableKeys() {
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(player("林恩", 70));
        stubCreate(7L);

        service.requestCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Check(
                        "调查书房",
                        CocCheckDifficulty.HARD,
                        new KpDiceRequestDTOs.CheckTarget(
                                "林恩", "侦查", CocPercentileModifier.BONUS_1,
                                "提前瞄准并观察书房")));

        @SuppressWarnings("unchecked")
        List<DiceRollResultCreateDTO> drafts =
                (List<DiceRollResultCreateDTO>) org.mockito.Mockito.mockingDetails(internal)
                        .getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName().equals("createDiceRoll"))
                        .findFirst()
                        .orElseThrow()
                        .getArgument(2);
        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.getFormula()).isEqualTo("1D100#");
            assertThat(draft.getResolutionData().getRule())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "cardId", 11L,
                            "characterName", "林恩",
                            "checkName", "侦查",
                            "targetValue", 70,
                            "difficulty", "HARD",
                            "modifier", "BONUS_1",
                            "pushed", false,
                            "modifierFactors", List.of(Map.of(
                                    "source", "KP",
                                    "kind", "BONUS",
                                    "diceCount", 1,
                                    "code", "KP_MODIFIER",
                                    "reason", "提前瞄准并观察书房"))));
        });
    }

    @Test
    void checkRejectsKpModifierWithoutAnExplanation() {
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(player("林恩", 70));

        assertThatThrownBy(() -> service.requestCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Check(
                        "调查书房",
                        CocCheckDifficulty.REGULAR,
                        new KpDiceRequestDTOs.CheckTarget(
                                "林恩", "侦查", CocPercentileModifier.BONUS_1))))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("奖惩骰原因");
    }

    @Test
    void firearmAttackPreallocatesAmmoAndStopsAfterTheLastAvailableGroup() {
        CocDiceCharacterVO attacker = card(
                11L, null, "林恩", Map.of("射击:冲锋枪", 40));
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "邪教徒甲")).thenReturn(
                card(21L, 91L, "邪教徒甲", Map.of()));
        when(cards.requireDiceCharacter(5L, "邪教徒乙")).thenReturn(
                card(22L, 92L, "邪教徒乙", Map.of()));
        CocCharacterWeapon weapon = new CocCharacterWeapon()
                .setId(81L)
                .setCharacterId(11L)
                .setName("汤普森冲锋枪")
                .setSkillName("射击:冲锋枪")
                .setDamage("1D10+2")
                .setAmmoCapacity(20)
                .setRemainingAmmo(6)
                .setMalfunction("96")
                .setCanImpale(true)
                .setIsBroken(false);
        when(cards.requireWeaponForUpdate(
                5L, "林恩", "汤普森冲锋枪"))
                .thenReturn(weapon);
        stubCreate(7L);

        KpDiceToolResult result = service.requestFirearmAttack(
                7L,
                5L,
                new KpFirearmRequestDTOs.Attack(
                        "向两名邪教徒扫射",
                        "林恩",
                        "汤普森冲锋枪",
                        FirearmFiringMode.FULL_AUTO,
                        true,
                        List.of(
                                new KpFirearmRequestDTOs.Target(
                                        "邪教徒甲", 4,
                                        CocPercentileModifier.NORMAL),
                                new KpFirearmRequestDTOs.Target(
                                        "邪教徒乙", 4,
                                        CocPercentileModifier.NORMAL))));

        assertThat(weapon.getRemainingAmmo()).isZero();
        verify(cards).updateWeapon(weapon);
        assertThat(result.results()).hasSize(2);
        assertThat(createdDrafts())
                .extracting(draft -> draft.getResolutionData()
                        .getRule().get("targetCharacterName"))
                .containsExactly("邪教徒甲", "邪教徒乙");
        assertThat(createdDrafts())
                .extracting(draft -> draft.getResolutionData()
                        .getRule().get("bulletsInGroup"))
                .containsExactly(4, 2);
        assertThat(createdDrafts())
                .allSatisfy(draft -> assertThat(
                        draft.getResolutionData().getRule())
                        .doesNotContainKey("rollBundleKey"));
        assertThat(createdDrafts().get(0).getResolutionData().getRule())
                .doesNotContainKey("modifierFactors");
        assertThat(createdDrafts().get(1).getResolutionData().getRule())
                .containsEntry("modifierFactors", List.of(Map.of(
                        "source", "BACKEND",
                        "kind", "PENALTY",
                        "diceCount", 1,
                        "code", "FIRING_MODE",
                        "reason", "全自动射击进入后续弹组")));
    }

    @Test
    void shotgunSelectsNearMediumAndFarDamageForEachTarget() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "猎人", Map.of("射击:步枪/霰弹枪", 60));
        when(cards.requireDiceCharacter(5L, "猎人")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "近处目标")).thenReturn(
                card(21L, 91L, "近处目标", Map.of()));
        when(cards.requireDiceCharacter(5L, "中距目标")).thenReturn(
                card(22L, 92L, "中距目标", Map.of()));
        when(cards.requireDiceCharacter(5L, "远处目标")).thenReturn(
                card(23L, 93L, "远处目标", Map.of()));
        CocCharacterWeapon weapon = shotgun("4D6/2D6/1D6", 3);
        when(cards.requireWeaponForUpdate(5L, "猎人", "泵动霰弹枪"))
                .thenReturn(weapon);
        stubCreate(7L);

        service.requestFirearmAttack(
                7L, 5L, new KpFirearmRequestDTOs.Attack(
                        "依次射击三个目标", "猎人", "泵动霰弹枪",
                        FirearmFiringMode.SINGLE, true,
                        List.of(
                                new KpFirearmRequestDTOs.Target(
                                        "近处目标", 1,
                                        CocPercentileModifier.NORMAL,
                                        FirearmDistance.NEAR, false),
                                new KpFirearmRequestDTOs.Target(
                                        "中距目标", 1,
                                        CocPercentileModifier.NORMAL,
                                        FirearmDistance.MEDIUM, false),
                                new KpFirearmRequestDTOs.Target(
                                        "远处目标", 1,
                                        CocPercentileModifier.NORMAL,
                                        FirearmDistance.FAR, false))));

        assertThat(createdDrafts())
                .extracting(draft -> draft.getResolutionData()
                        .getRule().get("damageFormula"))
                .containsExactly("4D6", "2D6", "1D6");
    }

    @Test
    void shotgunRequiresDistanceBeforeDeductingAmmo() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "猎人", Map.of("射击:步枪/霰弹枪", 60));
        when(cards.requireDiceCharacter(5L, "猎人")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "目标")).thenReturn(
                card(21L, 91L, "目标", Map.of()));
        CocCharacterWeapon weapon = shotgun("4D6/2D6/1D6", 2);
        when(cards.requireWeaponForUpdate(5L, "猎人", "泵动霰弹枪"))
                .thenReturn(weapon);

        assertThatThrownBy(() -> service.requestFirearmAttack(
                7L, 5L, new KpFirearmRequestDTOs.Attack(
                        "射击目标", "猎人", "泵动霰弹枪",
                        FirearmFiringMode.SINGLE, true,
                        List.of(new KpFirearmRequestDTOs.Target(
                                "目标", 1,
                                CocPercentileModifier.NORMAL)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distance");

        assertThat(weapon.getRemainingAmmo()).isEqualTo(2);
        verify(cards, never()).updateWeapon(any());
        verify(internal, never()).createDiceRoll(any(), any(), any());
    }

    @Test
    void ineffectiveShotgunDistanceIsRejectedBeforeDeductingAmmo() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "猎人", Map.of("射击:步枪/霰弹枪", 60));
        when(cards.requireDiceCharacter(5L, "猎人")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "目标")).thenReturn(
                card(21L, 91L, "目标", Map.of()));
        CocCharacterWeapon weapon = shotgun("4D6/1D6/0", 2);
        when(cards.requireWeaponForUpdate(5L, "猎人", "泵动霰弹枪"))
                .thenReturn(weapon);

        assertThatThrownBy(() -> service.requestFirearmAttack(
                7L, 5L, new KpFirearmRequestDTOs.Attack(
                        "远距离射击", "猎人", "泵动霰弹枪",
                        FirearmFiringMode.SINGLE, true,
                        List.of(new KpFirearmRequestDTOs.Target(
                                "目标", 1,
                                CocPercentileModifier.NORMAL,
                                FirearmDistance.FAR, false)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("该距离无效");

        assertThat(weapon.getRemainingAmmo()).isEqualTo(2);
        verify(cards, never()).updateWeapon(any());
        verify(internal, never()).createDiceRoll(any(), any(), any());
    }

    @Test
    void shotgunDamageRequiresExactlyThreeValidSlashSeparatedTiers() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "猎人", Map.of("射击:步枪/霰弹枪", 60));
        when(cards.requireDiceCharacter(5L, "猎人")).thenReturn(attacker);
        CocCharacterWeapon weapon = shotgun("4D6/2D6", 2);
        when(cards.requireWeaponForUpdate(5L, "猎人", "泵动霰弹枪"))
                .thenReturn(weapon);
        KpFirearmRequestDTOs.Attack request = new KpFirearmRequestDTOs.Attack(
                "射击目标", "猎人", "泵动霰弹枪",
                FirearmFiringMode.SINGLE, true,
                List.of(new KpFirearmRequestDTOs.Target(
                        "目标", 1, CocPercentileModifier.NORMAL,
                        FirearmDistance.NEAR, false)));

        assertThatThrownBy(() -> service.requestFirearmAttack(7L, 5L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A/B/C");

        weapon.setDamage("4D6//1D6");
        assertThatThrownBy(() -> service.requestFirearmAttack(7L, 5L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A/B/C");

        weapon.setDamage("4D6/2D6/1D6/1D3");
        assertThatThrownBy(() -> service.requestFirearmAttack(7L, 5L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A/B/C");

        weapon.setDamage("4D6/无效/1D6");
        assertThatThrownBy(() -> service.requestFirearmAttack(7L, 5L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("枪械伤害公式");
    }

    @Test
    void fixedDamageFirearmSilentlyIgnoresDistance() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "枪手", Map.of("射击:手枪", 60));
        when(cards.requireDiceCharacter(5L, "枪手")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "目标")).thenReturn(
                card(21L, 91L, "目标", Map.of()));
        CocCharacterWeapon weapon = new CocCharacterWeapon()
                .setId(81L).setCharacterId(11L)
                .setName("手枪").setSkillName("射击:手枪")
                .setDamage("1D10").setAmmoCapacity(6).setRemainingAmmo(1)
                .setMalfunction("100").setCanImpale(true)
                .setIsBroken(false);
        when(cards.requireWeaponForUpdate(5L, "枪手", "手枪"))
                .thenReturn(weapon);
        stubCreate(7L);

        service.requestFirearmAttack(
                7L, 5L, new KpFirearmRequestDTOs.Attack(
                        "射击目标", "枪手", "手枪",
                        FirearmFiringMode.SINGLE, true,
                        List.of(new KpFirearmRequestDTOs.Target(
                                "目标", 1,
                                CocPercentileModifier.NORMAL,
                                FirearmDistance.FAR, false))));

        assertThat(createdDrafts()).singleElement().satisfies(draft ->
                assertThat(draft.getResolutionData().getRule())
                        .containsEntry("damageFormula", "1D10"));
    }

    @Test
    void onePlayerClickRollsEveryFirearmAttackGroupTogether() {
        CocDiceCharacterVO attacker = card(
                11L, null, "林恩", Map.of("射击:冲锋枪", 40));
        CocDiceCharacterVO target = card(
                21L, 91L, "邪教徒", Map.of());
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "邪教徒")).thenReturn(target);
        CocCharacterWeapon weapon = new CocCharacterWeapon()
                .setId(81L)
                .setCharacterId(11L)
                .setName("汤普森冲锋枪")
                .setSkillName("射击:冲锋枪")
                .setDamage("1D10+2")
                .setAmmoCapacity(20)
                .setRemainingAmmo(12)
                .setMalfunction("96")
                .setCanImpale(true)
                .setIsBroken(false);
        when(cards.requireWeaponForUpdate(
                5L, "林恩", "汤普森冲锋枪"))
                .thenReturn(weapon);

        AtomicReference<List<DiceRollResult>> attacksRef =
                new AtomicReference<>();
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setReason("全自动扫射")
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        when(internal.createDiceRoll(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> attacks = materialize(
                            101L, 1, invocation.getArgument(2));
                    attacks.forEach(attack -> attack.setResultData(
                            DiceUtils.prepare("1D1")));
                    attacksRef.set(attacks);
                    return new DiceRollAggregate(summary, attacks);
                });
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> materialize(
                        101L, 2, invocation.getArgument(2)));

        service.requestFirearmAttack(
                7L,
                5L,
                new KpFirearmRequestDTOs.Attack(
                        "全自动扫射",
                        "林恩",
                        "汤普森冲锋枪",
                        FirearmFiringMode.FULL_AUTO,
                        true,
                        List.of(new KpFirearmRequestDTOs.Target(
                                "邪教徒", 12,
                                CocPercentileModifier.NORMAL))));
        when(internal.requireResult(201L))
                .thenAnswer(ignored -> attacksRef.get().getFirst());
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L))
                .thenAnswer(ignored -> attacksRef.get());

        service.rollPlayerResult(201L);

        assertThat(attacksRef.get()).hasSize(3)
                .allSatisfy(attack -> assertThat(attack.getResolvedAt())
                        .isNotNull());
    }

    @Test
    void firearmAttackAutomaticallyPenalizesTargetAlreadyInCover() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "枪手", Map.of("射击:手枪", 60));
        CocDiceCharacterVO target = cardInCover(
                21L, 91L, "掩护中的目标", Map.of());
        when(cards.requireDiceCharacter(5L, "枪手")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "掩护中的目标"))
                .thenReturn(target);
        CocCharacterWeapon weapon = new CocCharacterWeapon()
                .setId(81L).setCharacterId(11L)
                .setName("手枪").setSkillName("射击:手枪")
                .setDamage("1D10").setAmmoCapacity(6).setRemainingAmmo(1)
                .setMalfunction("100").setCanImpale(true)
                .setIsBroken(false);
        when(cards.requireWeaponForUpdate(5L, "枪手", "手枪"))
                .thenReturn(weapon);
        stubCreate(7L);

        service.requestFirearmAttack(
                7L, 5L, new KpFirearmRequestDTOs.Attack(
                        "射击掩护中的目标", "枪手", "手枪",
                        FirearmFiringMode.SINGLE, true,
                        List.of(new KpFirearmRequestDTOs.Target(
                                "掩护中的目标", 1,
                                CocPercentileModifier.NORMAL))));

        assertThat(createdDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getFormula()).isEqualTo("1D100$");
            assertThat(draft.getResolutionData().getRule())
                    .containsEntry("automaticSituationPenaltyDice", 1)
                    .containsEntry("modifierFactors", List.of(Map.of(
                            "source", "BACKEND",
                            "kind", "PENALTY",
                            "diceCount", 1,
                            "code", "TARGET_IN_COVER",
                            "reason", "目标“掩护中的目标”处于掩护中")));
        });
    }

    @Test
    void firearmAttackAutomaticallyCombinesMovementSmallTargetAndPosturePenalties() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "奔跑中的枪手", Map.of("射击:手枪", 60));
        CocDiceCharacterVO target = cardWithBuild(
                21L, 91L, "快速移动的小型目标", Map.of(), -2);
        when(cards.requireDiceCharacter(5L, "奔跑中的枪手"))
                .thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "快速移动的小型目标"))
                .thenReturn(target);
        CocCharacterWeapon weapon = new CocCharacterWeapon()
                .setId(81L).setCharacterId(11L)
                .setName("手枪").setSkillName("射击:手枪")
                .setDamage("1D10").setAmmoCapacity(6).setRemainingAmmo(1)
                .setMalfunction("100").setCanImpale(true)
                .setIsBroken(false);
        when(cards.requireWeaponForUpdate(5L, "奔跑中的枪手", "手枪"))
                .thenReturn(weapon);
        stubCreate(7L);

        service.requestFirearmAttack(
                7L, 5L, new KpFirearmRequestDTOs.Attack(
                        "在奔跑中射击小型移动目标",
                        "奔跑中的枪手",
                        "手枪",
                        FirearmFiringMode.SINGLE,
                        true,
                        true,
                        true,
                        List.of(new KpFirearmRequestDTOs.Target(
                                "快速移动的小型目标",
                                1,
                                CocPercentileModifier.BONUS_1,
                                "已经提前瞄准",
                                null,
                                true))));

        assertThat(createdDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getFormula()).isEqualTo("1D100$$");
            assertThat(draft.getResolutionData().getRule())
                    .containsEntry("automaticSituationPenaltyDice", 4)
                    .containsEntry("difficultyIncrease", 1)
                    .containsEntry("modifierFactors", List.of(
                            Map.of(
                                    "source", "KP",
                                    "kind", "BONUS",
                                    "diceCount", 1,
                                    "code", "KP_MODIFIER",
                                    "reason", "已经提前瞄准"),
                            Map.of(
                                    "source", "BACKEND",
                                    "kind", "PENALTY",
                                    "diceCount", 1,
                                    "code", "SHOOTER_MOVING_FAST",
                                    "reason", "射手正在高速移动"),
                            Map.of(
                                    "source", "BACKEND",
                                    "kind", "PENALTY",
                                    "diceCount", 1,
                                    "code", "FIRING_POSTURE_RESTRICTED",
                                    "reason", "射击姿势明显受限"),
                            Map.of(
                                    "source", "BACKEND",
                                    "kind", "PENALTY",
                                    "diceCount", 1,
                                    "code", "TARGET_MOVING_FAST",
                                    "reason", "目标“快速移动的小型目标”正在高速移动"),
                            Map.of(
                                    "source", "BACKEND",
                                    "kind", "PENALTY",
                                    "diceCount", 1,
                                    "code", "SMALL_TARGET",
                                    "reason", "目标“快速移动的小型目标”体型过小")));
        });
    }

    @Test
    void firearmMalfunctionKeepsEarlierDamageAndInvalidatesLaterGroups() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "枪手", Map.of("射击:冲锋枪", 40));
        CocDiceCharacterVO target = cardWithArmor(
                21L, 91L, "邪教徒", Map.of(), 3);
        when(cards.requireDiceCharacter(5L, "枪手")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "邪教徒")).thenReturn(target);
        CocCharacterWeapon weapon = new CocCharacterWeapon()
                .setId(81L).setCharacterId(11L)
                .setName("汤普森冲锋枪")
                .setSkillName("射击:冲锋枪")
                .setDamage("1D10+2")
                .setAmmoCapacity(20).setRemainingAmmo(12)
                .setMalfunction("96").setCanImpale(true)
                .setIsBroken(false);
        when(cards.requireWeaponForUpdate(
                5L, "枪手", "汤普森冲锋枪"))
                .thenReturn(weapon);
        CocCharacter targetEntity = damageCard(21L, "邪教徒")
                .setHpCurrent(100)
                .setHpMax(100)
                .setArmor(3);
        when(cards.lockDiceCharacter(5L, 21L)).thenReturn(targetEntity);
        when(internal.createDiceRoll(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts =
                            invocation.getArgument(2);
                    List<DiceRollResult> rows = materialize(101L, 1, drafts);
                    rows.get(0).setResultData(new DiceRollResultVO(
                            "1D100", List.of(), 20));
                    rows.get(1).setResultData(new DiceRollResultVO(
                            "1D100$", List.of(), 97));
                    rows.get(2).setResultData(new DiceRollResultVO(
                            "1D100$$", List.of(), 15));
                    return new DiceRollAggregate(
                            new DiceRollSummary().setId(101L)
                                    .setConversationId(7L)
                                    .setReason("扫射")
                                    .setRoundCount(1)
                                    .setStatus(DiceRollConstant.STATUS_COMPLETED),
                            rows);
                });
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            101L, 2, invocation.getArgument(2));
                    rows.forEach(row -> row.setResultData(
                            DiceUtils.roll(row.getResultData().getFormula())));
                    return rows;
                });

        KpDiceToolResult result = service.requestFirearmAttack(
                7L,
                5L,
                new KpFirearmRequestDTOs.Attack(
                        "扫射",
                        "枪手",
                        "汤普森冲锋枪",
                        FirearmFiringMode.FULL_AUTO,
                        true,
                        List.of(new KpFirearmRequestDTOs.Target(
                                "邪教徒", 12,
                                CocPercentileModifier.NORMAL))));

        assertThat(weapon.getRemainingAmmo()).isZero();
        assertThat(weapon.getIsBroken()).isTrue();
        assertThat(result.results()).hasSize(4);
        int appliedDamage = result.results().getLast()
                .getResultData().getResult();
        assertThat(targetEntity.getHpCurrent())
                .isEqualTo(100 - appliedDamage);
        assertThat(result.results().getLast().getResolution().getEffect())
                .containsEntry("hpLoss", appliedDamage);
        assertThat(result.results().subList(0, 3))
                .extracting(detail -> detail.getResolution()
                        .getOutcome().get("valid"))
                .containsExactly(true, false, false);
        @SuppressWarnings("unchecked")
        List<DiceRollResultCreateDTO> damageDrafts =
                (List<DiceRollResultCreateDTO>) org.mockito.Mockito
                        .mockingDetails(internal).getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName()
                                .equals("appendDiceRollRound"))
                        .findFirst().orElseThrow().getArgument(2);
        assertThat(damageDrafts).singleElement().satisfies(draft -> {
            assertThat(draft.getFormula())
                    .isEqualTo("max(0,(1D10+2)-3)+max(0,(1D10+2)-3)");
            assertThat(draft.getResolutionData().getRule())
                    .containsEntry("hitCount", 2)
                    .containsEntry("impalingHitCount", 0)
                    .containsEntry("armor", 3)
                    .containsEntry("automaticArmor", true);
        });
    }

    @Test
    void firearmStunWeaponCreatesSeparateDamageAndStunDice() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "电击手", Map.of("射击:手枪", 60));
        CocDiceCharacterVO target = card(
                21L, 91L, "目标", Map.of());
        when(cards.requireDiceCharacter(5L, "电击手")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "目标")).thenReturn(target);
        CocCharacterWeapon weapon = new CocCharacterWeapon()
                .setId(81L).setCharacterId(11L)
                .setName("泰瑟枪").setSkillName("射击:手枪")
                .setDamage("1D3+眩晕")
                .setAmmoCapacity(1).setRemainingAmmo(1)
                .setMalfunction("95").setCanImpale(false)
                .setIsBroken(false);
        when(cards.requireWeaponForUpdate(
                5L, "电击手", "泰瑟枪")).thenReturn(weapon);
        CocCharacter targetEntity = damageCard(21L, "目标");
        when(cards.lockDiceCharacter(5L, 21L)).thenReturn(targetEntity);
        when(internal.createDiceRoll(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            101L, 1, invocation.getArgument(2));
                    rows.getFirst().setResultData(new DiceRollResultVO(
                            "1D100", List.of(), 20));
                    return new DiceRollAggregate(
                            new DiceRollSummary().setId(101L)
                                    .setConversationId(7L)
                                    .setReason("电击")
                                    .setRoundCount(1)
                                    .setStatus(DiceRollConstant.STATUS_COMPLETED),
                            rows);
                });
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            101L, 2, invocation.getArgument(2));
                    rows.get(0).setResultData(new DiceRollResultVO(
                            "(1D3)", List.of(), 2));
                    rows.get(1).setResultData(new DiceRollResultVO(
                            "1D6", List.of(), 4));
                    return rows;
                });

        KpDiceToolResult result = service.requestFirearmAttack(
                7L, 5L, new KpFirearmRequestDTOs.Attack(
                        "电击", "电击手", "泰瑟枪",
                        FirearmFiringMode.SINGLE, true,
                        List.of(new KpFirearmRequestDTOs.Target(
                                "目标", 1, CocPercentileModifier.NORMAL))));

        assertThat(result.results())
                .extracting(detail -> detail.getResolution().getType())
                .containsExactly("FIREARM_ATTACK", "DAMAGE", "STUN_DURATION");
        assertThat(targetEntity.getHpCurrent()).isEqualTo(8);
        assertThat(targetEntity.getStunnedRemainingRounds()).isEqualTo(4);
        assertThat(result.semanticResult())
                .contains("目标生命-2")
                .contains("目标被眩晕4回合");
    }

    @Test
    void meleeExtremeKnifeAttackAutomaticallyAppendsImpalingDamage() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "林恩", Map.of("斗殴", 60));
        CocDiceCharacterVO defender = cardWithArmor(
                21L, 91L, "邪教徒", Map.of("闪避", 40), 3);
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "邪教徒")).thenReturn(defender);
        CocCharacter attackerEntity = damageCard(11L, "林恩")
                .setDamageBonus("+1D4");
        CocCharacter defenderEntity = damageCard(21L, "邪教徒")
                .setHpCurrent(100)
                .setHpMax(100)
                .setDamageBonus("0")
                .setArmor(3);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(attackerEntity);
        when(cards.lockDiceCharacter(5L, 21L)).thenReturn(defenderEntity);
        when(cards.requireWeaponForUpdate(5L, "林恩", "折刀"))
                .thenReturn(new CocCharacterWeapon()
                        .setId(81L).setName("折刀").setSkillName("斗殴")
                        .setDamage("1D4+2+DB").setCanImpale(true)
                        .setIsBroken(false));
        when(internal.createDiceRoll(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            101L, 1, invocation.getArgument(2));
                    rows.get(0).setResultData(new DiceRollResultVO(
                            "1D100", List.of(), 5));
                    rows.get(1).setResultData(new DiceRollResultVO(
                            "1D100", List.of(), 80));
                    return new DiceRollAggregate(
                            new DiceRollSummary().setId(101L)
                                    .setConversationId(7L).setReason("刺击")
                                    .setRoundCount(1)
                                    .setStatus(DiceRollConstant.STATUS_COMPLETED),
                            rows);
                });
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            101L, 2, invocation.getArgument(2));
                    rows.forEach(row -> row.setResultData(
                            DiceUtils.roll(row.getResultData().getFormula())));
                    return rows;
                });

        KpDiceToolResult result = service.requestMeleeAttack(
                7L, 5L, new KpMeleeRequestDTOs.Attack(
                        "刺击",
                        new KpMeleeRequestDTOs.Attacker(
                                "林恩", "折刀",
                                CocPercentileModifier.NORMAL),
                        new KpMeleeRequestDTOs.Defender(
                                "邪教徒", MeleeDefenseMode.DODGE,
                                null, CocPercentileModifier.NORMAL)));

        assertThat(result.results()).hasSize(3);
        int appliedDamage = result.results().getLast()
                .getResultData().getResult();
        assertThat(defenderEntity.getHpCurrent())
                .isEqualTo(100 - appliedDamage);
        assertThat(result.results().getLast().getResolution().getEffect())
                .containsEntry("hpLoss", appliedDamage);
        @SuppressWarnings("unchecked")
        List<DiceRollResultCreateDTO> damageDrafts =
                (List<DiceRollResultCreateDTO>) org.mockito.Mockito
                        .mockingDetails(internal).getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName()
                                .equals("appendDiceRollRound"))
                        .findFirst().orElseThrow().getArgument(2);
        assertThat(damageDrafts).singleElement().satisfies(draft -> {
            assertThat(draft.getFormula())
                    .isEqualTo("max(0,(10+(1D4))-3)");
            assertThat(draft.getResolutionData().getRule())
                    .containsEntry("characterName", "邪教徒")
                    .containsEntry("sourceCharacterName", "林恩")
                    .containsEntry("impaling", true)
                    .containsEntry("armor", 3)
                    .containsEntry("automaticArmor", true);
        });
    }

    @Test
    void npcMeleeMajorWoundLeavesTheWoundedPlayersConRollPending() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "阿尔法", Map.of("斗殴", 60));
        CocDiceCharacterVO defender = card(
                21L, null, "本", Map.of());
        when(cards.requireDiceCharacter(5L, "阿尔法")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "本")).thenReturn(defender);
        CocCharacter attackerEntity = damageCard(11L, "阿尔法")
                .setDamageBonus("0");
        CocCharacter defenderEntity = damageCard(21L, "本")
                .setDamageBonus("0");
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(attackerEntity);
        when(cards.lockDiceCharacter(5L, 21L)).thenReturn(defenderEntity);
        when(cards.requireWeaponForUpdate(5L, "阿尔法", "伸缩警棍"))
                .thenReturn(new CocCharacterWeapon()
                        .setId(82L).setName("伸缩警棍").setSkillName("斗殴")
                        .setDamage("6").setCanImpale(false)
                        .setIsBroken(false));
        when(internal.createDiceRoll(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            101L, 1, invocation.getArgument(2));
                    rows.getFirst().setResultData(new DiceRollResultVO(
                            "1D100", List.of(), 20));
                    return new DiceRollAggregate(
                            new DiceRollSummary().setId(101L)
                                    .setConversationId(7L)
                                    .setReason("阿尔法攻击本")
                                    .setRoundCount(1)
                                    .setStatus(DiceRollConstant.STATUS_COMPLETED),
                            rows);
                });
        AtomicLong round = new AtomicLong(1L);
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            101L,
                            Math.toIntExact(round.incrementAndGet()),
                            invocation.getArgument(2));
                    if (DiceRollConstant.TYPE_DAMAGE.equals(
                            rows.getFirst().getResolutionData().getType())) {
                        rows.getFirst().setResultData(
                                new DiceRollResultVO("6", List.of(), 6));
                    }
                    return rows;
                });

        KpDiceToolResult result = service.requestMeleeAttack(
                7L, 5L, new KpMeleeRequestDTOs.Attack(
                        "阿尔法攻击本",
                        new KpMeleeRequestDTOs.Attacker(
                                "阿尔法", "伸缩警棍",
                                CocPercentileModifier.NORMAL),
                        new KpMeleeRequestDTOs.Defender(
                                "本", MeleeDefenseMode.NONE,
                                null, CocPercentileModifier.NORMAL)));

        assertThat(result.results()).hasSize(3);
        assertThat(result.results().getLast()).satisfies(con -> {
            assertThat(con.getResolution().getType())
                    .isEqualTo(DiceRollConstant.TYPE_MAJOR_WOUND_CON);
            assertThat(con.getCharacterId()).isNull();
            assertThat(con.getResultData().getResult()).isNull();
            assertThat(con.getResultData().getModules()).isNotEmpty();
            assertThat(con.getResolution().getCheckName()).isEqualTo("CON");
        });
        assertThat(result.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_PENDING);
    }

    @Test
    void meleeStunWeaponCreatesSeparateDamageAndStunDice() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "电击手", Map.of("斗殴", 60));
        CocDiceCharacterVO defender = card(
                21L, 91L, "目标", Map.of());
        when(cards.requireDiceCharacter(5L, "电击手")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "目标")).thenReturn(defender);
        CocCharacter attackerEntity = damageCard(11L, "电击手")
                .setDamageBonus("0");
        CocCharacter defenderEntity = damageCard(21L, "目标")
                .setDamageBonus("0");
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(attackerEntity);
        when(cards.lockDiceCharacter(5L, 21L)).thenReturn(defenderEntity);
        when(cards.requireWeaponForUpdate(5L, "电击手", "电击器"))
                .thenReturn(new CocCharacterWeapon()
                        .setId(82L).setName("电击器").setSkillName("斗殴")
                        .setDamage("1D3+眩晕").setCanImpale(false)
                        .setIsBroken(false));
        when(internal.createDiceRoll(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            101L, 1, invocation.getArgument(2));
                    rows.getFirst().setResultData(new DiceRollResultVO(
                            "1D100", List.of(), 20));
                    return new DiceRollAggregate(
                            new DiceRollSummary().setId(101L)
                                    .setConversationId(7L)
                                    .setReason("电击")
                                    .setRoundCount(1)
                                    .setStatus(DiceRollConstant.STATUS_COMPLETED),
                            rows);
                });
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            101L, 2, invocation.getArgument(2));
                    rows.get(0).setResultData(new DiceRollResultVO(
                            "1D3", List.of(), 2));
                    rows.get(1).setResultData(new DiceRollResultVO(
                            "1D6", List.of(), 4));
                    return rows;
                });

        KpDiceToolResult result = service.requestMeleeAttack(
                7L, 5L, new KpMeleeRequestDTOs.Attack(
                        "电击",
                        new KpMeleeRequestDTOs.Attacker(
                                "电击手", "电击器",
                                CocPercentileModifier.NORMAL),
                        new KpMeleeRequestDTOs.Defender(
                                "目标", MeleeDefenseMode.NONE,
                                null, CocPercentileModifier.NORMAL)));

        assertThat(result.results())
                .extracting(detail -> detail.getResolution().getType())
                .containsExactly("MELEE_ATTACK", "DAMAGE", "STUN_DURATION");
        assertThat(defenderEntity.getHpCurrent()).isEqualTo(8);
        assertThat(defenderEntity.getStunnedRemainingRounds()).isEqualTo(4);
    }

    @Test
    void meleeCounterattackWinnerUsesOrdinaryDamageEvenOnExtremeSuccess() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "林恩", Map.of("斗殴", 40));
        CocDiceCharacterVO defender = card(
                21L, 91L, "邪教徒", Map.of("斗殴", 60));
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "邪教徒")).thenReturn(defender);
        CocCharacter attackerEntity = damageCard(11L, "林恩")
                .setDamageBonus("0");
        CocCharacter defenderEntity = damageCard(21L, "邪教徒")
                .setDamageBonus("+1D4");
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(attackerEntity);
        when(cards.lockDiceCharacter(5L, 21L)).thenReturn(defenderEntity);
        when(cards.requireWeaponForUpdate(5L, "邪教徒", "大棒"))
                .thenReturn(new CocCharacterWeapon()
                        .setId(82L).setName("大棒").setSkillName("斗殴")
                        .setDamage("1D8+DB").setCanImpale(false)
                        .setIsBroken(false));
        when(internal.createDiceRoll(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            101L, 1, invocation.getArgument(2));
                    rows.get(0).setResultData(new DiceRollResultVO(
                            "1D100", List.of(), 80));
                    rows.get(1).setResultData(new DiceRollResultVO(
                            "1D100", List.of(), 5));
                    return new DiceRollAggregate(
                            new DiceRollSummary().setId(101L)
                                    .setConversationId(7L).setReason("反击")
                                    .setRoundCount(1)
                                    .setStatus(DiceRollConstant.STATUS_COMPLETED),
                            rows);
                });
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> materialize(
                        101L, 2, invocation.getArgument(2)));

        service.requestMeleeAttack(
                7L, 5L, new KpMeleeRequestDTOs.Attack(
                        "徒手攻击遭到反击",
                        new KpMeleeRequestDTOs.Attacker(
                                "林恩", null,
                                CocPercentileModifier.NORMAL),
                        new KpMeleeRequestDTOs.Defender(
                                "邪教徒", MeleeDefenseMode.COUNTERATTACK,
                                "大棒", CocPercentileModifier.NORMAL)));

        @SuppressWarnings("unchecked")
        List<DiceRollResultCreateDTO> damageDrafts =
                (List<DiceRollResultCreateDTO>) org.mockito.Mockito
                        .mockingDetails(internal).getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName()
                                .equals("appendDiceRollRound"))
                        .findFirst().orElseThrow().getArgument(2);
        assertThat(damageDrafts).singleElement().satisfies(draft -> {
            assertThat(draft.getFormula()).isEqualTo("1D8+(1D4)");
            assertThat(draft.getResolutionData().getRule())
                    .containsEntry("characterName", "林恩")
                    .containsEntry("sourceCharacterName", "邪教徒")
                    .containsEntry("maximumDamage", false);
        });
    }

    @Test
    void repeatedMeleeAttackMarksDefenderAndAddsAutomaticBonusDie() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "林恩", Map.of("斗殴", 40));
        CocDiceCharacterVO defender = card(
                21L, 91L, "邪教徒", Map.of("闪避", 30));
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "邪教徒")).thenReturn(defender);
        CocCharacter attackerEntity = damageCard(11L, "林恩")
                .setDamageBonus("0");
        CocCharacter defenderEntity = damageCard(21L, "邪教徒")
                .setDamageBonus("0")
                .setMeleeAttackedThisRound(false);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(attackerEntity);
        when(cards.lockDiceCharacter(5L, 21L)).thenReturn(defenderEntity);
        stubCreate(7L);
        KpMeleeRequestDTOs.Attack request = new KpMeleeRequestDTOs.Attack(
                "连续围攻",
                new KpMeleeRequestDTOs.Attacker(
                        "林恩", null, CocPercentileModifier.BONUS_1,
                        "队友正在牵制防守者"),
                new KpMeleeRequestDTOs.Defender(
                        "邪教徒", MeleeDefenseMode.DODGE,
                        null, CocPercentileModifier.PENALTY_1,
                        "防守者视线受阻"));

        service.requestMeleeAttack(7L, 5L, request);
        service.requestMeleeAttack(7L, 5L, request);

        assertThat(defenderEntity.getMeleeAttackedThisRound()).isTrue();
        verify(cards).updateDiceCharacter(defenderEntity);
        List<String> attackerFormulas = org.mockito.Mockito
                .mockingDetails(internal).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName()
                        .equals("createDiceRoll"))
                .map(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<DiceRollResultCreateDTO> drafts =
                            invocation.getArgument(2);
                    return drafts.getFirst().getFormula();
                })
                .toList();
        assertThat(attackerFormulas)
                .containsExactly("1D100#", "1D100##");
        @SuppressWarnings("unchecked")
        List<DiceRollResultCreateDTO> secondDrafts =
                (List<DiceRollResultCreateDTO>) org.mockito.Mockito
                        .mockingDetails(internal).getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName()
                                .equals("createDiceRoll"))
                        .skip(1)
                        .findFirst().orElseThrow().getArgument(2);
        assertThat(secondDrafts).hasSize(2);
        assertThat(secondDrafts.get(0).getResolutionData().getRule())
                .containsEntry("modifierFactors", List.of(
                        Map.of(
                                "source", "KP",
                                "kind", "BONUS",
                                "diceCount", 1,
                                "code", "KP_MODIFIER",
                                "reason", "队友正在牵制防守者"),
                        Map.of(
                                "source", "BACKEND",
                                "kind", "BONUS",
                                "diceCount", 1,
                                "code", "DEFENDER_ALREADY_ATTACKED",
                                "reason", "防守者本轮已经遭受过近战攻击")));
        assertThat(secondDrafts.get(1).getResolutionData().getRule())
                .containsEntry("modifierFactors", List.of(Map.of(
                        "source", "KP",
                        "kind", "PENALTY",
                        "diceCount", 1,
                        "code", "KP_MODIFIER",
                        "reason", "防守者视线受阻")));
    }

    @Test
    void handgunUsedInMeleeBecomesSmallClubWithoutChangingTheWeapon() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "林恩", Map.of("斗殴", 55, "射击:手枪", 70));
        CocDiceCharacterVO defender = card(
                21L, 91L, "邪教徒", Map.of());
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "邪教徒")).thenReturn(defender);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(
                damageCard(11L, "林恩").setDamageBonus("0"));
        when(cards.lockDiceCharacter(5L, 21L)).thenReturn(
                damageCard(21L, "邪教徒").setDamageBonus("0"));
        CocCharacterWeapon handgun = new CocCharacterWeapon()
                .setId(81L).setName("左轮手枪")
                .setSkillName("射击:手枪").setDamage("1D10")
                .setRemainingAmmo(4).setCanImpale(true).setIsBroken(false);
        when(cards.requireWeaponForUpdate(5L, "林恩", "左轮手枪"))
                .thenReturn(handgun);
        stubCreate(7L);

        service.requestMeleeAttack(
                7L, 5L, new KpMeleeRequestDTOs.Attack(
                        "用枪托砸击",
                        new KpMeleeRequestDTOs.Attacker(
                                "林恩", "左轮手枪",
                                CocPercentileModifier.NORMAL),
                        new KpMeleeRequestDTOs.Defender(
                                "邪教徒", MeleeDefenseMode.NONE,
                                null, CocPercentileModifier.NORMAL)));

        assertThat(createdDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getResolutionData().getRule())
                    .containsEntry("checkName", "斗殴")
                    .containsEntry("weaponName", "小型棍棒（枪托替代）")
                    .containsEntry("damageFormula", "1D6+DB")
                    .containsEntry("canImpale", false);
        });
        assertThat(handgun.getRemainingAmmo()).isEqualTo(4);
        verify(cards, never()).updateWeapon(handgun);
    }

    @Test
    void throwingWeaponUsedInMeleeBecomesLargeClub() {
        CocDiceCharacterVO attacker = card(
                11L, 88L, "林恩", Map.of("斗殴", 55, "投掷", 70));
        CocDiceCharacterVO defender = card(
                21L, 91L, "邪教徒", Map.of());
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(attacker);
        when(cards.requireDiceCharacter(5L, "邪教徒")).thenReturn(defender);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(
                damageCard(11L, "林恩").setDamageBonus("0"));
        when(cards.lockDiceCharacter(5L, 21L)).thenReturn(
                damageCard(21L, "邪教徒").setDamageBonus("0"));
        when(cards.requireWeaponForUpdate(5L, "林恩", "飞刀"))
                .thenReturn(new CocCharacterWeapon()
                        .setId(83L).setName("飞刀")
                        .setSkillName("投掷").setDamage("1D4+半DB")
                        .setCanImpale(true).setIsBroken(false));
        stubCreate(7L);

        service.requestMeleeAttack(
                7L, 5L, new KpMeleeRequestDTOs.Attack(
                        "握住飞刀砸击",
                        new KpMeleeRequestDTOs.Attacker(
                                "林恩", "飞刀",
                                CocPercentileModifier.NORMAL),
                        new KpMeleeRequestDTOs.Defender(
                                "邪教徒", MeleeDefenseMode.NONE,
                                null, CocPercentileModifier.NORMAL)));

        assertThat(createdDrafts()).singleElement().satisfies(draft ->
                assertThat(draft.getResolutionData().getRule())
                        .containsEntry("checkName", "斗殴")
                        .containsEntry("weaponName", "大型棍棒（枪托替代）")
                        .containsEntry("damageFormula", "1D8+DB")
                        .containsEntry("canImpale", false));
    }

    @Test
    void singleCheckUsesHighestValueAmongCandidateCheckNames() {
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(
                card(11L, null, "林恩", Map.of("追踪", 40, "侦查", 70)));
        stubCreate(7L);

        service.requestCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Check(
                        "寻找足迹",
                        CocCheckDifficulty.REGULAR,
                        new KpDiceRequestDTOs.CheckTarget(
                                "林恩",
                                List.of("追踪", "侦查"),
                                CocPercentileModifier.NORMAL)));

        assertThat(createdDrafts()).singleElement().satisfies(draft ->
                assertThat(draft.getResolutionData().getRule())
                        .containsEntry("checkName", "侦查")
                        .containsEntry("targetValue", 70));
    }

    @Test
    void groupCheckUsesEachCharactersHighestCandidateCheckValue() {
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(
                card(11L, null, "林恩", Map.of("追踪", 40, "侦查", 70)));
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(
                card(12L, 12L, "陈默", Map.of("追踪", 65, "侦查", 35)));
        stubCreate(7L);

        service.requestGroupCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.GroupCheck(
                        "寻找足迹",
                        CocCheckDifficulty.REGULAR,
                        null,
                        List.of(
                                new KpDiceRequestDTOs.CheckTarget(
                                        "林恩", List.of("追踪", "侦查"),
                                        CocPercentileModifier.NORMAL),
                                new KpDiceRequestDTOs.CheckTarget(
                                        "陈默", List.of("追踪", "侦查"),
                                        CocPercentileModifier.NORMAL))));

        assertThat(createdDrafts())
                .extracting(draft -> draft.getResolutionData().getRule().get("checkName"))
                .containsExactly("侦查", "追踪");
        assertThat(createdDrafts())
                .extracting(draft -> draft.getResolutionData().getRule().get("targetValue"))
                .containsExactly(70, 65);
    }

    @Test
    void groupCheckRequiresAtLeastTwoCharacters() {
        assertThatThrownBy(() -> service.requestGroupCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.GroupCheck(
                        "只有一人参与",
                        CocCheckDifficulty.REGULAR,
                        com.me.galchat.constant.GroupCheckRule.SEPARATE,
                        List.of(target("林恩", "聆听")))))
                .hasMessageContaining("群体检定至少需要两个角色");

        verify(cards, never()).requireDiceCharacter(any(), any());
    }

    @Test
    void opposedResultReturnsWinnerInsteadOfRawRanks() {
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(player("林恩", 70));
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(agent(12L, "陈默", 45));
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            List<DiceRollResult> materialized = materialize(101L, 1, drafts);
            materialized.get(0)
                    .setCharacterId(99L)
                    .setResultData(new DiceRollResultVO("1D100", List.of(), 35));
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(101L)
                            .setConversationId(7L)
                            .setRoundCount(1)
                            .setStatus(DiceRollConstant.STATUS_COMPLETED),
                    materialized);
        });

        KpDiceToolResult result = service.requestOpposedCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Opposed(
                        "争夺手枪",
                        List.of(
                                target("林恩", "侦查"),
                                target("陈默", "侦查")),
                        null));

        assertThat(result.semanticResult()).isEqualTo("林恩获胜");
        assertThat(result.semanticResult()).doesNotContain("困难", "极难");
    }

    @Test
    void onePlayerClickRollsBothUnkeyedOpposedChecksTogether() {
        when(cards.requireDiceCharacter(5L, "林恩"))
                .thenReturn(player("林恩", 70));
        when(cards.requireDiceCharacter(5L, "陈默"))
                .thenReturn(card(12L, null, "陈默", 45));
        AtomicReference<List<DiceRollResult>> checksRef =
                new AtomicReference<>();
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setReason("争夺手枪")
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        when(internal.createDiceRoll(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> checks = materialize(
                            101L, 1, invocation.getArgument(2));
                    checks.forEach(check -> check.setResultData(
                            DiceUtils.prepare("1D1")));
                    checksRef.set(checks);
                    return new DiceRollAggregate(summary, checks);
                });

        service.requestOpposedCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Opposed(
                        "争夺手枪",
                        List.of(
                                target("林恩", "侦查"),
                                target("陈默", "侦查")),
                        null));
        when(internal.requireResult(201L))
                .thenAnswer(ignored -> checksRef.get().getFirst());
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L))
                .thenAnswer(ignored -> checksRef.get());

        service.rollPlayerResult(201L);

        assertThat(checksRef.get()).hasSize(2)
                .allSatisfy(check -> assertThat(check.getResolvedAt())
                        .isNotNull());
    }

    @Test
    void pushedCheckUsesExplicitSummaryInsteadOfLatestCompatibleSummary() {
        when(followUps.requireLatestSummaryId(7L, Set.of(
                DiceRollConstant.TOOL_REQUEST_CHECK,
                DiceRollConstant.TOOL_REQUEST_GROUP_CHECK)))
                .thenReturn(102L);
        DiceRollSummary previous = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult failed = resolvedCheck(
                201L, 101L, 1, "林恩", 11L, 70, "FAILURE");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(previous);
        when(internal.listResultEntities(101L)).thenReturn(List.of(failed));
        when(cards.requireDiceCharacter(5L, "林恩"))
                .thenReturn(player("林恩", 70));
        DiceRollSummary latest = new DiceRollSummary()
                .setId(102L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult latestFailure = resolvedCheck(
                202L, 102L, 1, "林恩", 11L, 70, "FAILURE");
        when(internal.requireSummaryForUpdate(102L)).thenReturn(latest);
        when(internal.listResultEntities(102L)).thenReturn(List.of(latestFailure));
        when(internal.appendDiceRollRound(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    Long summaryId = invocation.getArgument(1);
                    return materialize(summaryId, 2, drafts);
                });

        KpDiceToolResult result = service.requestPushedCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Pushed(
                        "孤注一掷搜索密室",
                        101L,
                        CocCheckDifficulty.REGULAR,
                        null,
                        List.of(target("林恩", "侦查"))));

        assertThat(result.summary().getId()).isEqualTo(101L);
        assertThat(result.results()).singleElement().satisfies(pushed -> {
            assertThat(pushed.getRoundNo()).isEqualTo(2);
            assertThat(pushed.getResolution().getSourceResultId()).isNull();
            assertThat(pushed.getResolution().getOutcome()).isNull();
        });
        verify(followUps, never()).requireLatestSummaryId(any(), any());
    }

    @Test
    void pushedCheckUsesFreshExecutorSkillDifficultyAndModifier() {
        DiceRollSummary previous = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult oldCheck = resolvedCheck(
                201L, 101L, 1, "林恩", 11L, 70, "FAILURE");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(previous);
        when(internal.listResultEntities(101L)).thenReturn(List.of(oldCheck));
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(
                card(12L, 88L, "陈默", Map.of("急救", 60, "医学", 80)));
        when(internal.appendDiceRollRound(any(), any(), any()))
                .thenAnswer(invocation -> materialize(
                        101L, 2, invocation.getArgument(2)));

        KpDiceToolResult result = service.requestPushedCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Pushed(
                        "陈默改用医学处理伤势",
                        101L,
                        CocCheckDifficulty.HARD,
                        null,
                        List.of(new KpDiceRequestDTOs.CheckTarget(
                                "陈默",
                                List.of("急救", "医学"),
                                CocPercentileModifier.BONUS_1,
                                "改用更合适的医学方法"))));

        assertThat(result.results()).singleElement().satisfies(pushed -> {
            assertThat(pushed.getResolution().getCharacterName()).isEqualTo("陈默");
            assertThat(pushed.getResolution().getCheckName()).isEqualTo("医学");
            assertThat(pushed.getResolution().getTargetValue()).isEqualTo(40);
            assertThat(pushed.getResolution().getDifficulty()).isEqualTo("HARD");
            assertThat(pushed.getResolution().getSourceResultId()).isNull();
            assertThat(pushed.getResultData().getFormula()).isEqualTo("1D100#");
        });
    }

    @Test
    void pushedCheckUsesFreshGroupTargetsAndRule() {
        DiceRollSummary previous = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(previous);
        when(internal.listResultEntities(101L)).thenReturn(List.of(
                resolvedCheck(201L, 101L, 1, "旧执行者", 10L, 50, "FAILURE")));
        when(cards.requireDiceCharacter(5L, "林恩"))
                .thenReturn(player("林恩", 70));
        when(cards.requireDiceCharacter(5L, "陈默"))
                .thenReturn(card(12L, 88L, "陈默", Map.of("医学", 80)));
        when(internal.appendDiceRollRound(any(), any(), any()))
                .thenAnswer(invocation -> materialize(
                        101L, 2, invocation.getArgument(2)));

        KpDiceToolResult result = service.requestPushedCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Pushed(
                        "两人换方法继续救治",
                        101L,
                        CocCheckDifficulty.REGULAR,
                        GroupCheckRule.ANY_SUCCESS,
                        List.of(
                                target("林恩", "侦查"),
                                new KpDiceRequestDTOs.CheckTarget(
                                        "陈默", "医学", CocPercentileModifier.PENALTY_1,
                                        "现场医疗条件不足"))));

        assertThat(result.results()).hasSize(2).allSatisfy(pushed ->
                assertThat(pushed.getResolution().getGroupRule())
                        .isEqualTo("ANY_SUCCESS"));
        assertThat(result.results())
                .extracting(pushed -> pushed.getResolution().getCharacterName())
                .containsExactly("林恩", "陈默");
    }

    @Test
    void pushedCheckDoesNotRevalidateKpEligibilityDecision() {
        DiceRollSummary previous = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult successful = resolvedCheck(
                201L, 101L, 1, "林恩", 11L, 70, "SUCCESS");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(previous);
        when(internal.listResultEntities(101L)).thenReturn(List.of(successful));
        when(cards.requireDiceCharacter(5L, "林恩"))
                .thenReturn(player("林恩", 70));
        when(internal.appendDiceRollRound(any(), any(), any()))
                .thenAnswer(invocation -> materialize(
                        101L, 2, invocation.getArgument(2)));

        assertThatCode(() -> service.requestPushedCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Pushed(
                        "KP判定可以孤注一掷",
                        101L,
                        null,
                        null,
                        List.of(target("林恩", "侦查")))))
                .doesNotThrowAnyException();
    }

    @Test
    void pushedCheckRejectsNonCheckSummary() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(
                resolvedSanCheck(
                        201L, 101L, "林恩", 11L, null, "FAILURE")));

        assertThatThrownBy(() -> service.requestPushedCheck(
                7L,
                5L,
                new KpDiceRequestDTOs.Pushed(
                        "错误关联",
                        101L,
                        null,
                        null,
                        List.of(target("林恩", "侦查")))))
                .hasMessageContaining("不是普通检定");
    }

    @Test
    void retryDoesNotRerollOrReapplySanLoss() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED)
                .setTotalResult("林恩理智-6");
        DiceRollResult resolved = sanLoss(
                201L,
                101L,
                "林恩",
                11L,
                null,
                new DiceRollResultVO("1D6", List.of(), 6),
                6)
                .setRoundNo(1)
                .setResolvedAt(LocalDateTime.now());
        when(internal.requireResult(201L)).thenReturn(resolved);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);

        var result = service.rollPlayerResult(201L);

        assertThat(result.rolledResult().getId()).isEqualTo(201L);
        assertThat(result.summary().getTotalResult()).isEqualTo("林恩理智-6");
        verify(internal, never()).saveResult(any());
        verify(internal, never()).saveSummary(any());
        verify(cards, never()).lockDiceCharacter(any(), any());
        verify(cards, never()).updateDiceCharacter(any());
    }

    @Test
    void playerRollResolvesStoredRuleAndRebuildsSummary() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult pending = resolvedCheck(
                201L, 101L, 1, "林恩", 11L, 70, "FAILURE")
                .setCharacterId(null)
                .setResultData(DiceUtils.prepare("1D1"))
                .setResolvedAt(null);
        pending.getResolutionData().setOutcome(null);
        when(internal.requireResult(201L)).thenReturn(pending);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(pending));

        var result = service.rollPlayerResult(201L);

        assertThat(result.rolledResult().getResultData().getResult()).isEqualTo(1);
        assertThat(result.rolledResult().getResolution().getOutcome())
                .containsEntry("category", "CRITICAL_SUCCESS");
        assertThat(result.summary().getStatus()).isEqualTo(DiceRollConstant.STATUS_COMPLETED);
        assertThat(result.summary().getTotalResult())
                .isEqualTo("林恩进行“侦查”检定：大成功");
        verify(internal).saveResult(pending);
        verify(internal).saveSummary(summary);
    }

    @Test
    void completedRoundOnlyIsIncludedInTotalResult() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(2)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult completed = resolvedCheck(
                200L, 101L, 1, "陈默", 12L, 45, "SUCCESS")
                .setCharacterId(88L);
        DiceRollResult rolling = resolvedCheck(
                201L, 101L, 2, "林恩", 11L, 70, "FAILURE")
                .setCharacterId(null)
                .setResultData(DiceUtils.prepare("1D1"))
                .setResolvedAt(null);
        rolling.getResolutionData().setOutcome(null);
        rolling.getResolutionData().getRule()
                .put("rollBundleKey", "check:林恩");
        DiceRollResult stillPending = resolvedCheck(
                202L, 101L, 2, "周晴", 13L, 60, "FAILURE")
                .setCharacterId(null)
                .setResultData(DiceUtils.prepare("1D100"))
                .setResolvedAt(null);
        stillPending.getResolutionData().setOutcome(null);
        stillPending.getResolutionData().getRule()
                .put("rollBundleKey", "check:周晴");
        when(internal.requireResult(201L)).thenReturn(rolling);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L))
                .thenReturn(List.of(completed, rolling, stillPending));

        var progress = service.rollPlayerResult(201L);

        assertThat(progress.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_PENDING);
        assertThat(progress.summary().getTotalResult())
                .isEqualTo("陈默进行“侦查”检定：成功");
        verify(internal).saveSummary(summary);
        verify(internal, never()).appendDiceRollRound(any(), any(), any());
    }

    @Test
    void playerCannotUseRollEndpointForAgentResult() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult agentResult = resolvedCheck(
                201L, 101L, 1, "陈默", 12L, 45, "SUCCESS")
                .setCharacterId(88L);
        when(internal.requireResult(201L)).thenReturn(agentResult);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);

        assertThatThrownBy(() -> service.rollPlayerResult(201L))
                .hasMessageContaining("不是玩家");
        verify(internal, never()).saveResult(any());
        verify(cards, never()).updateDiceCharacter(any());
    }

    @Test
    void oldRoundPendingResultCannotBeRolledAfterRoundAdvance() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(2)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult stale = resolvedCheck(
                201L, 101L, 1, "林恩", 11L, 70, "FAILURE")
                .setCharacterId(null)
                .setResultData(DiceUtils.prepare("1D100"))
                .setResolvedAt(null);
        stale.getResolutionData().setOutcome(null);
        when(internal.requireResult(201L)).thenReturn(stale);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);

        assertThatThrownBy(() -> service.rollPlayerResult(201L))
                .hasMessageContaining("当前掷骰轮次");

        verify(internal, never()).saveResult(any());
        verify(internal, never()).saveSummary(any());
        verify(cards, never()).updateDiceCharacter(any());
    }

    @Test
    void sanLossUsesActualBranchAndDefersMadnessWhilePlayerDiceIsPending() {
        when(followUps.requireLatestSummaryId(7L, Set.of("requestSanCheck")))
                .thenReturn(101L);
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult playerCheck = resolvedSanCheck(
                201L, 101L, "林恩", 11L, null, "FAILURE");
        DiceRollResult agentCheck = resolvedSanCheck(
                202L, 101L, "陈默", 12L, 88L, "FAILURE");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L))
                .thenReturn(List.of(playerCheck, agentCheck));
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    List<DiceRollResult> created = materialize(101L, 2, drafts);
                    created.get(1).setResultData(
                            new DiceRollResultVO("1D6", List.of(), 6));
                    return created;
                });
        CocCharacter agentCard = new CocCharacter()
                .setId(12L)
                .setRunId(5L)
                .setName("陈默")
                .setSanCurrent(60);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(agentCard);

        KpDiceToolResult created = service.rollSanLoss(
                7L,
                5L,
                new KpDiceRequestDTOs.SanLoss("目睹怪物", "0", "1D6"));

        assertThat(created.summary().getRoundCount()).isEqualTo(2);
        assertThat(created.results())
                .filteredOn(result -> result.getCharacterId() == null)
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.getResultData().getFormula()).isEqualTo("1D6");
                    assertThat(result.getResultData().getResult()).isNull();
                });
        verify(internal, times(1))
                .appendDiceRollRound(eq(7L), eq(101L), any());
    }

    @Test
    void sanConstantZeroDoesNotCreateClickablePlaceholder() {
        when(followUps.requireLatestSummaryId(7L, Set.of("requestSanCheck")))
                .thenReturn(101L);
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult playerCheck = resolvedSanCheck(
                201L, 101L, "林恩", 11L, null, "SUCCESS");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(playerCheck));
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    List<DiceRollResult> created = materialize(101L, 2, drafts);
                    created.getFirst().setResultData(
                            new DiceRollResultVO("0", List.of(), 0));
                    return created;
                });
        CocCharacter card = new CocCharacter()
                .setId(11L)
                .setRunId(5L)
                .setName("林恩")
                .setSanCurrent(60);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(card);

        KpDiceToolResult result = service.rollSanLoss(
                7L,
                5L,
                new KpDiceRequestDTOs.SanLoss("看到尸体", "0", "1D6"));

        assertThat(result.results()).singleElement().satisfies(detail -> {
            assertThat(detail.getResultData().getFormula()).isEqualTo("0");
            assertThat(detail.getResultData().getModules()).isEmpty();
            assertThat(detail.getResultData().getResult()).isZero();
        });
        assertThat(result.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_COMPLETED);
        assertThat(result.semanticResult()).isEqualTo("林恩理智-0");
        verify(internal, times(1))
                .appendDiceRollRound(eq(7L), eq(101L), any());
    }

    @Test
    void madnessRoundIsCreatedOnceUnderSummaryLock() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(2)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult playerLoss = sanLoss(
                301L, 101L, "林恩", 11L, null, DiceUtils.prepare("1D1*6"), null);
        DiceRollResult agentLoss = sanLoss(
                302L,
                101L,
                "陈默",
                12L,
                88L,
                new DiceRollResultVO("1D6", List.of(), 5),
                5);
        when(internal.requireResult(301L)).thenReturn(playerLoss);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L))
                .thenReturn(List.of(playerLoss, agentLoss));
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    List<DiceRollResult> created = materialize(101L, 3, drafts);
                    for (DiceRollResult result : created) {
                        if (result.getCharacterId() != null) {
                            result.setResultData(new DiceRollResultVO("1D10", List.of(), 4));
                        }
                    }
                    return created;
                });
        CocCharacter playerCard = new CocCharacter()
                .setId(11L)
                .setRunId(5L)
                .setName("林恩")
                .setSanCurrent(60);
        CocCharacter agentCard = new CocCharacter()
                .setId(12L)
                .setRunId(5L)
                .setName("陈默")
                .setSanCurrent(55);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(playerCard);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(agentCard);

        var progress = service.rollPlayerResult(301L);
        var retry = service.rollPlayerResult(301L);

        assertThat(progress.createdResults()).hasSize(4);
        assertThat(progress.createdResults())
                .extracting(com.me.galchat.domain.vo.DiceRollDetailVO::getRoundNo)
                .containsOnly(3);
        assertThat(retry.createdResults()).isEmpty();
        verify(internal, times(2)).requireSummaryForUpdate(101L);
        verify(internal, times(1))
                .appendDiceRollRound(eq(7L), eq(101L), any());
        verify(messageRoundAppender).appendRounds(7L, 101L, List.of(3));
    }

    @Test
    void agentOnlySanLossCreatesAndSettlesMadnessRoundImmediately() {
        when(followUps.requireLatestSummaryId(7L, Set.of("requestSanCheck")))
                .thenReturn(101L);
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult agentCheck = resolvedSanCheck(
                201L, 101L, "陈默", 12L, 88L, "FAILURE");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(agentCheck));
        AtomicLong appendCount = new AtomicLong();
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    int round = appendCount.incrementAndGet() == 1 ? 2 : 3;
                    List<DiceRollResult> created = materialize(101L, round, drafts);
                    for (DiceRollResult result : created) {
                        String type = result.getResolutionData().getType();
                        int value = switch (type) {
                            case "SAN_LOSS" -> 6;
                            case "TEMPORARY_INSANITY_TYPE" -> 9;
                            default -> 4;
                        };
                        result.setResultData(new DiceRollResultVO(
                                result.getResultData().getFormula(), List.of(), value));
                    }
                    return created;
                });
        when(randomSource.d100()).thenReturn(37);
        CocCharacter card = new CocCharacter()
                .setId(12L)
                .setRunId(5L)
                .setName("陈默")
                .setSanCurrent(60);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(card);

        KpDiceToolResult result = service.rollSanLoss(
                7L,
                5L,
                new KpDiceRequestDTOs.SanLoss("目睹怪物", "0", "1D6"));

        assertThat(result.summary().getRoundCount()).isEqualTo(3);
        assertThat(result.results()).hasSize(3);
        assertThat(result.semanticResult())
                .contains("陈默理智-6；进入临时疯狂：恐惧症（昆虫恐惧症：害怕昆虫），持续4小时");
        assertThat(card.getSanCurrent()).isEqualTo(54);
        assertThat(card.getTemporaryInsanityPhase()).isEqualTo("9:037");
        verify(internal, times(2))
                .appendDiceRollRound(eq(7L), eq(101L), any());
    }

    @Test
    void typeNineStoresRandomCatalogNumberWithoutCreatingD100Result() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(3)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        DiceRollResult type = insanityResult(
                401L,
                "TEMPORARY_INSANITY_TYPE",
                new DiceRollResultVO("1D10", List.of(), 9),
                true);
        type.getResolutionData().setOutcome(Map.of(
                "characterName", "林恩", "typeRoll", 9));
        DiceRollResult duration = insanityResult(
                402L,
                "TEMPORARY_INSANITY_DURATION",
                DiceUtils.prepare("1D1*4"),
                false);
        when(internal.requireResult(402L)).thenReturn(duration);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(type, duration));
        when(randomSource.d100()).thenReturn(37);
        CocCharacter card = new CocCharacter()
                .setId(11L)
                .setRunId(5L)
                .setName("林恩")
                .setSanCurrent(54);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(card);

        service.rollPlayerResult(402L);

        assertThat(List.of(type, duration))
                .noneMatch(result -> "1D100".equals(result.getResultData().getFormula()));
        assertThat(card.getTemporaryInsanityPhase()).isEqualTo("9:037");
        assertThat(card.getTemporaryInsanityRemainingHours()).isEqualTo(4);
        verify(randomSource).d100();
        verify(messageRoundAppender, never())
                .appendRounds(any(), any(), any());
    }

    @Test
    void standaloneDamageCreatesFirstRoundAndAppliesHp() {
        CocDiceCharacterVO targetCard = cardWithArmor(
                12L, 88L, "陈默", Map.of("侦查", 45), 3);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(targetCard);
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            DiceRollResultCreateDTO draft = drafts.getFirst();
            DiceRollResult result = new DiceRollResult()
                    .setId(501L)
                    .setSummaryId(111L)
                    .setCharacterId(draft.getCharacterId())
                    .setRoundNo(1)
                    .setDisplayOrder(1)
                    .setResultData(new DiceRollResultVO("3", List.of(), 3))
                    .setResolutionData(draft.getResolutionData());
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(111L)
                            .setConversationId(7L)
                            .setRoundCount(1)
                            .setStatus(DiceRollConstant.STATUS_COMPLETED),
                    List.of(result));
        });
        CocCharacter locked = new CocCharacter()
                .setId(12L)
                .setRunId(5L)
                .setName("陈默")
                .setHpCurrent(10)
                .setHpMax(10)
                .setCon(50)
                .setArmor(3)
                .setMajorWound(false)
                .setUnconscious(false)
                .setDead(false);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollDamage(
                7L,
                5L,
                new KpDiceRequestDTOs.Damage(
                        "坠入坑中",
                        List.of(new KpDiceRequestDTOs.DamageTarget(
                                "陈默", "3"))));

        assertThat(result.summary().getRoundCount()).isEqualTo(1);
        assertThat(locked.getHpCurrent()).isEqualTo(7);
        assertThat(result.semanticResult()).contains("陈默生命-3");
    }

    @Test
    void standaloneDamageSplitsHpAndStunAndKeepsTheLongerDuration() {
        CocDiceCharacterVO targetCard = card(
                12L, 88L, "陈默", 45);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(targetCard);
        when(internal.createDiceRoll(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            111L, 1, invocation.getArgument(2));
                    rows.get(0).setResultData(new DiceRollResultVO(
                            "1D3", List.of(), 2));
                    rows.get(1).setResultData(new DiceRollResultVO(
                            "1D6", List.of(), 3));
                    return new DiceRollAggregate(
                            new DiceRollSummary()
                                    .setId(111L)
                                    .setConversationId(7L)
                                    .setReason("电击")
                                    .setRoundCount(1)
                                    .setStatus(DiceRollConstant.STATUS_COMPLETED),
                            rows);
                });
        CocCharacter locked = damageCard(12L, "陈默")
                .setStunnedRemainingRounds(5);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollDamage(
                7L, 5L, new KpDiceRequestDTOs.Damage(
                        "电击",
                        List.of(new KpDiceRequestDTOs.DamageTarget(
                                "陈默", "1D3+眩晕"))));

        assertThat(result.results())
                .extracting(detail -> detail.getResolution().getType())
                .containsExactly("DAMAGE", "STUN_DURATION");
        assertThat(locked.getHpCurrent()).isEqualTo(8);
        assertThat(locked.getStunnedRemainingRounds()).isEqualTo(5);
        assertThat(result.semanticResult())
                .isEqualTo("陈默生命-2；陈默被眩晕5回合（本次1D6=3，维持原时长）");
    }

    @Test
    void pureStunDoesNotCreateHpDamage() {
        CocDiceCharacterVO targetCard = card(
                12L, 88L, "陈默", 45);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(targetCard);
        when(internal.createDiceRoll(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            111L, 1, invocation.getArgument(2));
                    rows.getFirst().setResultData(new DiceRollResultVO(
                            "1D6", List.of(), 4));
                    return new DiceRollAggregate(
                            new DiceRollSummary()
                                    .setId(111L)
                                    .setConversationId(7L)
                                    .setReason("催泪喷雾")
                                    .setRoundCount(1)
                                    .setStatus(DiceRollConstant.STATUS_COMPLETED),
                            rows);
                });
        CocCharacter locked = damageCard(12L, "陈默");
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollDamage(
                7L, 5L, new KpDiceRequestDTOs.Damage(
                        "催泪喷雾",
                        List.of(new KpDiceRequestDTOs.DamageTarget(
                                "陈默", "眩晕"))));

        assertThat(result.results()).singleElement().satisfies(detail -> {
            assertThat(detail.getResolution().getType())
                    .isEqualTo("STUN_DURATION");
            assertThat(detail.getResultData().getFormula()).isEqualTo("1D6");
        });
        assertThat(locked.getHpCurrent()).isEqualTo(10);
        assertThat(locked.getStunnedRemainingRounds()).isEqualTo(4);
    }

    @Test
    void onePlayerClickRollsDamageAndStunFromTheSameTargetTogether() {
        CocDiceCharacterVO targetCard = card(
                12L, null, "林恩", 70);
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(targetCard);
        AtomicReference<List<DiceRollResult>> rowsRef = new AtomicReference<>();
        DiceRollSummary summary = new DiceRollSummary()
                .setId(111L)
                .setConversationId(7L)
                .setReason("电击")
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        when(internal.createDiceRoll(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResult> rows = materialize(
                            111L, 1, invocation.getArgument(2));
                    rows.get(0).setResultData(DiceUtils.prepare("1D1*2"));
                    rows.get(1).setResultData(DiceUtils.prepare("1D6"));
                    rowsRef.set(rows);
                    return new DiceRollAggregate(summary, rows);
                });
        CocCharacter locked = damageCard(12L, "林恩");
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        service.rollDamage(
                7L, 5L, new KpDiceRequestDTOs.Damage(
                        "电击",
                        List.of(new KpDiceRequestDTOs.DamageTarget(
                                "林恩", "1D1*2+眩晕"))));
        when(internal.requireResult(201L))
                .thenAnswer(ignored -> rowsRef.get().getFirst());
        when(internal.requireSummaryForUpdate(111L)).thenReturn(summary);
        when(internal.listResultEntities(111L))
                .thenAnswer(ignored -> rowsRef.get());

        service.rollPlayerResult(201L);

        assertThat(rowsRef.get())
                .allSatisfy(row -> assertThat(row.getResolvedAt()).isNotNull());
        assertThat(locked.getHpCurrent()).isEqualTo(8);
        assertThat(locked.getStunnedRemainingRounds()).isBetween(1, 6);
        verify(internal, times(2)).saveResult(any());
    }

    @Test
    void majorWoundCharacterReachingZeroStartsDying() {
        CocDiceCharacterVO targetCard = card(12L, 88L, "陈默", 45);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(targetCard);
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            DiceRollResultCreateDTO draft = drafts.getFirst();
            DiceRollResult result = new DiceRollResult()
                    .setId(501L)
                    .setSummaryId(111L)
                    .setCharacterId(draft.getCharacterId())
                    .setRoundNo(1)
                    .setDisplayOrder(1)
                    .setResultData(new DiceRollResultVO("4", List.of(), 4))
                    .setResolutionData(draft.getResolutionData());
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(111L)
                            .setConversationId(7L)
                            .setRoundCount(1)
                            .setStatus(DiceRollConstant.STATUS_COMPLETED),
                    List.of(result));
        });
        CocCharacter locked = damageCard(12L, "陈默")
                .setHpCurrent(4)
                .setMajorWound(true)
                .setDying(false);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        service.rollDamage(
                7L,
                5L,
                new KpDiceRequestDTOs.Damage(
                        "持续失血",
                        List.of(new KpDiceRequestDTOs.DamageTarget(
                                "陈默", "4"))));

        assertThat(locked.getHpCurrent()).isZero();
        assertThat(locked.getUnconscious()).isTrue();
        assertThat(locked.getDying()).isTrue();
        verify(combatLifecycleService)
                .forfeitCurrentRoundSlot(7L, 12L);
    }

    @Test
    void standaloneHealingClampsAppliedHpAtMaximum() {
        CocDiceCharacterVO targetCard = card(12L, 88L, "陈默", 45);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(targetCard);
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            DiceRollResultCreateDTO draft = drafts.getFirst();
            DiceRollResult result = new DiceRollResult()
                    .setId(501L)
                    .setSummaryId(111L)
                    .setCharacterId(draft.getCharacterId())
                    .setRoundNo(1)
                    .setDisplayOrder(1)
                    .setResultData(new DiceRollResultVO("4", List.of(), 4))
                    .setResolutionData(draft.getResolutionData());
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(111L)
                            .setConversationId(7L)
                            .setRoundCount(1)
                            .setStatus(DiceRollConstant.STATUS_COMPLETED),
                    List.of(result));
        });
        CocCharacter locked = damageCard(12L, "陈默")
                .setHpCurrent(8);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "包扎伤口",
                        HealingSourceMode.STANDALONE,
                        HealingMode.OTHER,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", null, "4"))));

        assertThat(locked.getHpCurrent()).isEqualTo(10);
        assertThat(result.semanticResult()).isEqualTo("陈默生命+2");
        assertThat(result.results()).singleElement().satisfies(healing ->
                assertThat(healing.getResolution().getEffect())
                        .containsEntry("hpBefore", 8)
                        .containsEntry("hpAfter", 10)
                        .containsEntry("hpGain", 2));
    }

    @Test
    void healingAboveZeroStopsDyingButKeepsUnconsciousAndMajorWound() {
        CocDiceCharacterVO targetCard = card(12L, 88L, "陈默", 45);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(targetCard);
        stubCreate(7L);
        CocCharacter locked = damageCard(12L, "陈默")
                .setHpCurrent(0)
                .setMajorWound(true)
                .setUnconscious(true)
                .setDying(true);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "急救止血",
                        HealingSourceMode.STANDALONE,
                        HealingMode.OTHER,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", null, "1"))));

        assertThat(locked.getHpCurrent()).isEqualTo(10);
        assertThat(locked.getDying()).isFalse();
        assertThat(locked.getUnconscious()).isTrue();
        assertThat(locked.getMajorWound()).isTrue();
    }

    @Test
    void firstAidHealingClearsInjuryStatuses() {
        when(cards.requireDiceCharacter(5L, "陈默"))
                .thenReturn(card(12L, 88L, "陈默", 45));
        stubCreate(7L);
        CocCharacter locked = damageCard(12L, "陈默")
                .setHpCurrent(5)
                .setMajorWound(true)
                .setUnconscious(true);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "急救处理",
                        HealingSourceMode.STANDALONE,
                        HealingMode.FIRST_AID,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", null, "1"))));

        assertThat(locked.getMajorWound()).isFalse();
        assertThat(locked.getUnconscious()).isFalse();
        assertThat(result.semanticResult())
                .isEqualTo("陈默生命+5；解除重伤；脱离昏迷");
        assertThat(result.results()).singleElement().satisfies(healing ->
                assertThat(healing.getResolution().getEffect())
                        .containsEntry("majorWoundBefore", true)
                        .containsEntry("majorWound", false)
                        .containsEntry("majorWoundChanged", true)
                        .containsEntry("unconsciousBefore", true)
                        .containsEntry("unconscious", false)
                        .containsEntry("unconsciousChanged", true));
    }

    @Test
    void medicineHealingClearsOnlyMajorWound() {
        when(cards.requireDiceCharacter(5L, "陈默"))
                .thenReturn(card(12L, 88L, "陈默", 45));
        stubCreate(7L);
        CocCharacter locked = damageCard(12L, "陈默")
                .setHpCurrent(5)
                .setMajorWound(true)
                .setUnconscious(true);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "医学治疗",
                        HealingSourceMode.STANDALONE,
                        HealingMode.MEDICINE,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", null, "1"))));

        assertThat(locked.getMajorWound()).isFalse();
        assertThat(locked.getUnconscious()).isTrue();
        assertThat(result.semanticResult())
                .isEqualTo("陈默生命+5；解除重伤");
    }

    @Test
    void healingRequiresARecoveryMode() {
        assertThatThrownBy(() -> service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "来源不明的治疗",
                        HealingSourceMode.STANDALONE,
                        null,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", null, "1")))))
                .hasMessageContaining("恢复方式不能为空");

        verify(cards, never()).requireDiceCharacter(any(), any());
        verify(internal, never()).createDiceRoll(any(), any(), any());
    }

    @Test
    void followUpHealingAppendsToSuccessfulSingleCheck() {
        when(followUps.requireLatestSummaryId(
                7L,
                Set.of(
                        DiceRollConstant.TOOL_REQUEST_CHECK,
                        DiceRollConstant.TOOL_REQUEST_GROUP_CHECK,
                        DiceRollConstant.TOOL_REQUEST_PUSHED_CHECK)))
                .thenReturn(101L);
        DiceRollSummary summary = new DiceRollSummary()
                .setId(101L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_COMPLETED);
        DiceRollResult source = resolvedCheck(
                201L, 101L, 1, "林恩", 11L, 70, "SUCCESS");
        when(internal.requireSummaryForUpdate(101L)).thenReturn(summary);
        when(internal.listResultEntities(101L)).thenReturn(List.of(source));
        when(internal.appendDiceRollRound(eq(7L), eq(101L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    List<DiceRollResult> results = materialize(101L, 2, drafts);
                    results.getFirst().setResultData(
                            new DiceRollResultVO("3", List.of(), 3));
                    return results;
                });
        when(cards.requireDiceCharacter(5L, "陈默"))
                .thenReturn(card(12L, 88L, "陈默", 45));
        CocCharacter locked = damageCard(12L, "陈默").setHpCurrent(4);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(locked);

        KpDiceToolResult result = service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "急救伤口",
                        HealingSourceMode.FOLLOW_UP,
                        HealingMode.OTHER,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", "林恩", "3"))));

        assertThat(result.summary().getId()).isEqualTo(101L);
        assertThat(result.summary().getRoundCount()).isEqualTo(2);
        assertThat(locked.getHpCurrent()).isEqualTo(7);
        assertThat(result.results()).singleElement().satisfies(healing -> {
            assertThat(healing.getRoundNo()).isEqualTo(2);
            assertThat(healing.getResolution().getSourceResultId())
                    .isEqualTo(201L);
        });
    }

    @Test
    void followUpHealingRejectsFailedSingleCheck() {
        when(followUps.requireLatestSummaryId(eq(7L), any()))
                .thenReturn(101L);
        when(internal.requireSummaryForUpdate(101L)).thenReturn(
                new DiceRollSummary()
                        .setId(101L)
                        .setConversationId(7L)
                        .setRoundCount(1)
                        .setStatus(DiceRollConstant.STATUS_COMPLETED));
        when(internal.listResultEntities(101L)).thenReturn(List.of(
                resolvedCheck(201L, 101L, 1, "林恩", 11L, 70, "FAILURE")));

        assertThatThrownBy(() -> service.rollHealing(
                7L,
                5L,
                new KpDiceRequestDTOs.Healing(
                        "急救伤口",
                        HealingSourceMode.FOLLOW_UP,
                        HealingMode.OTHER,
                        List.of(new KpDiceRequestDTOs.HealingTarget(
                                "陈默", "林恩", "1")))))
                .hasMessageContaining("前置检定未成功");

        verify(internal, never()).appendDiceRollRound(any(), any(), any());
        verify(cards, never()).requireDiceCharacter(any(), any());
    }

    @Test
    void majorWoundConRoundIsCreatedOnceUnderSummaryLock() {
        CocDiceCharacterVO playerTarget = card(11L, null, "林恩", 70);
        CocDiceCharacterVO agentTarget = card(12L, 88L, "陈默", 45);
        when(cards.requireDiceCharacter(5L, "林恩")).thenReturn(playerTarget);
        when(cards.requireDiceCharacter(5L, "陈默")).thenReturn(agentTarget);
        AtomicReference<List<DiceRollResult>> damageRows = new AtomicReference<>();
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            List<DiceRollResult> rows = materialize(111L, 1, drafts);
            rows.get(0).setId(501L).setResultData(DiceUtils.prepare("1D1*6"));
            rows.get(1)
                    .setId(502L)
                    .setResultData(new DiceRollResultVO("6", List.of(), 6));
            damageRows.set(rows);
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(111L)
                            .setConversationId(7L)
                            .setRoundCount(1)
                            .setStatus(DiceRollConstant.STATUS_PENDING),
                    rows);
        });
        CocCharacter playerCard = damageCard(11L, "林恩");
        CocCharacter agentCard = damageCard(12L, "陈默");
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(playerCard);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(agentCard);

        KpDiceToolResult created = service.rollDamage(
                7L,
                5L,
                new KpDiceRequestDTOs.Damage(
                        "爆炸冲击",
                        List.of(
                                new KpDiceRequestDTOs.DamageTarget(
                                        "林恩", "1D1*6"),
                                new KpDiceRequestDTOs.DamageTarget(
                                        "陈默", "6"))));

        assertThat(created.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_PENDING);
        assertThat(created.summary().getRoundCount()).isEqualTo(1);
        verify(internal, never()).appendDiceRollRound(eq(7L), eq(111L), any());

        DiceRollSummary summary = new DiceRollSummary()
                .setId(111L)
                .setConversationId(7L)
                .setRoundCount(1)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        when(internal.requireResult(501L)).thenAnswer(
                ignored -> damageRows.get().getFirst());
        when(internal.requireSummaryForUpdate(111L)).thenReturn(summary);
        when(internal.listResultEntities(111L)).thenAnswer(
                ignored -> damageRows.get());
        when(internal.appendDiceRollRound(eq(7L), eq(111L), any()))
                .thenAnswer(invocation -> {
                    List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
                    List<DiceRollResult> conRows = materialize(111L, 2, drafts);
                    for (DiceRollResult con : conRows) {
                        if (con.getCharacterId() != null) {
                            con.setResultData(
                                    new DiceRollResultVO("1D100", List.of(), 35));
                        }
                    }
                    return conRows;
                });

        var progress = service.rollPlayerResult(501L);
        var retry = service.rollPlayerResult(501L);

        assertThat(progress.createdResults()).hasSize(2);
        assertThat(progress.createdResults())
                .extracting(result -> result.getResolution().getType())
                .containsOnly("MAJOR_WOUND_CON");
        assertThat(progress.createdResults())
                .extracting(com.me.galchat.domain.vo.DiceRollDetailVO::getRoundNo)
                .containsOnly(2);
        assertThat(retry.createdResults()).isEmpty();
        verify(internal, times(2)).requireSummaryForUpdate(111L);
        verify(internal, times(1))
                .appendDiceRollRound(eq(7L), eq(111L), any());
        verify(messageRoundAppender).appendRounds(7L, 111L, List.of(2));
    }

    @Test
    void failedMajorWoundConMakesCharacterUnconscious() {
        DiceRollSummary summary = new DiceRollSummary()
                .setId(111L)
                .setConversationId(7L)
                .setRoundCount(2)
                .setStatus(DiceRollConstant.STATUS_PENDING);
        var resolution = com.me.galchat.domain.vo.DiceResolutionDataVO.pending(
                "MAJOR_WOUND_CON",
                501L,
                Map.of(
                        "runId", 5L,
                        "cardId", 11L,
                        "characterName", "林恩",
                        "targetValue", 50,
                        "hpLoss", 6));
        DiceRollResult pending = new DiceRollResult()
                .setId(601L)
                .setSummaryId(111L)
                .setCharacterId(null)
                .setRoundNo(2)
                .setDisplayOrder(1)
                .setResultData(DiceUtils.prepare("1D1*100"))
                .setResolutionData(resolution);
        when(internal.requireResult(601L)).thenReturn(pending);
        when(internal.requireSummaryForUpdate(111L)).thenReturn(summary);
        when(internal.listResultEntities(111L)).thenReturn(List.of(pending));
        CocCharacter card = damageCard(11L, "林恩")
                .setHpCurrent(4)
                .setMajorWound(true);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(card);

        var progress = service.rollPlayerResult(601L);

        assertThat(progress.rolledResult().getResolution().getOutcome())
                .containsEntry("category", "FUMBLE");
        assertThat(card.getUnconscious()).isTrue();
        assertThat(progress.summary().getTotalResult())
                .isEqualTo("林恩生命-6；受到重伤；CON检定失败，陷入昏迷");
    }

    @Test
    void unconsciousPlayerRecoveryCreatesPendingConDie() {
        CocCharacter card = damageCard(11L, "林恩")
                .setActorType("PLAYER")
                .setUnconscious(true);
        when(cards.lockDiceCharacter(5L, 11L)).thenReturn(card);
        stubCreate(7L);

        KpDiceToolResult result = service.requestUnconsciousRecovery(
                7L, 5L, 11L);

        assertThat(result.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_PENDING);
        assertThat(result.results()).singleElement().satisfies(die -> {
            assertThat(die.getResolution().getType())
                    .isEqualTo("UNCONSCIOUS_RECOVERY_CON");
            assertThat(die.getResultData().getResult()).isNull();
        });
        assertThat(card.getUnconscious()).isTrue();
    }

    @Test
    void unconsciousAgentRecoverySettlesAndWakesWithoutAnotherAgentTurn() {
        CocCharacter card = damageCard(12L, "陈默")
                .setActorType("BOT")
                .setParticipantId(88L)
                .setUnconscious(true);
        when(cards.lockDiceCharacter(5L, 12L)).thenReturn(card);
        stubCreate(7L);

        KpDiceToolResult result = service.requestUnconsciousRecovery(
                7L, 5L, 12L);

        assertThat(result.summary().getStatus())
                .isEqualTo(DiceRollConstant.STATUS_COMPLETED);
        assertThat(result.semanticResult()).isEqualTo("陈默CON检定成功，脱离昏迷");
        assertThat(card.getUnconscious()).isFalse();
    }

    private void stubCreate(Long conversationId) {
        when(internal.createDiceRoll(any(), any(), any())).thenAnswer(invocation -> {
            String reason = invocation.getArgument(1);
            List<DiceRollResultCreateDTO> drafts = invocation.getArgument(2);
            List<DiceRollResult> results = materialize(101L, 1, drafts);
            boolean pending = results.stream().anyMatch(result ->
                    result.getCharacterId() == null
                            && result.getResultData().getResult() == null);
            return new DiceRollAggregate(
                    new DiceRollSummary()
                            .setId(101L)
                            .setConversationId(conversationId)
                            .setReason(reason)
                            .setRoundCount(1)
                            .setStatus(pending
                                    ? DiceRollConstant.STATUS_PENDING
                                    : DiceRollConstant.STATUS_COMPLETED),
                    results);
        });
    }

    private List<DiceRollResult> materialize(
            Long summaryId, int roundNo, List<DiceRollResultCreateDTO> drafts) {
        AtomicLong ids = new AtomicLong(201L);
        List<DiceRollResult> results = new ArrayList<>();
        for (DiceRollResultCreateDTO draft : drafts) {
            boolean player = draft.getCharacterId() == null;
            DiceRollResultVO resultData = player
                    ? DiceUtils.prepare(draft.getFormula())
                    : new DiceRollResultVO(draft.getFormula(), List.of(), 35);
            results.add(new DiceRollResult()
                    .setId(ids.getAndIncrement())
                    .setSummaryId(summaryId)
                    .setCharacterId(draft.getCharacterId())
                    .setRoundNo(roundNo)
                    .setDisplayOrder(draft.getDisplayOrder())
                    .setReason(draft.getReason())
                    .setResultData(resultData)
                    .setResolutionData(draft.getResolutionData()));
        }
        return results;
    }

    private DiceRollResult resolvedCheck(
            Long id,
            Long summaryId,
            int roundNo,
            String name,
            Long cardId,
            int target,
            String category) {
        var resolution = com.me.galchat.domain.vo.DiceResolutionDataVO.pending(
                "CHECK",
                null,
                Map.of(
                        "cardId", cardId,
                        "characterName", name,
                        "checkName", "侦查",
                        "targetValue", target,
                        "difficulty", "REGULAR",
                        "modifier", "NORMAL",
                        "pushed", false))
                .setOutcome(Map.of(
                        "characterName", name,
                        "checkName", "侦查",
                        "category", category));
        return new DiceRollResult()
                .setId(id)
                .setSummaryId(summaryId)
                .setRoundNo(roundNo)
                .setDisplayOrder(1)
                .setResultData(new DiceRollResultVO("1D100", List.of(), 75))
                .setResolutionData(resolution)
                .setResolvedAt(LocalDateTime.now());
    }

    private DiceRollResult resolvedSanCheck(
            Long id,
            Long summaryId,
            String name,
            Long cardId,
            Long characterId,
            String category) {
        DiceRollResult result = resolvedCheck(
                id, summaryId, 1, name, cardId, 60, category);
        result.setCharacterId(characterId);
        result.getResolutionData().setType("SAN_CHECK");
        result.getResolutionData().getRule().put("checkName", "理智");
        return result;
    }

    private DiceRollResult sanLoss(
            Long id,
            Long summaryId,
            String name,
            Long cardId,
            Long characterId,
            DiceRollResultVO resultData,
            Integer settledLoss) {
        var resolution = com.me.galchat.domain.vo.DiceResolutionDataVO.pending(
                "SAN_LOSS",
                id - 100,
                Map.of(
                        "runId", 5L,
                        "cardId", cardId,
                        "characterName", name,
                        "sanCheckOutcome", "FAILURE"));
        DiceRollResult result = new DiceRollResult()
                .setId(id)
                .setSummaryId(summaryId)
                .setCharacterId(characterId)
                .setRoundNo(2)
                .setDisplayOrder(id.intValue() - 300)
                .setResultData(resultData)
                .setResolutionData(resolution);
        if (settledLoss != null) {
            resolution
                    .setOutcome(Map.of("characterName", name, "sanLoss", settledLoss))
                    .setEffect(Map.of(
                            "sanBefore", 60,
                            "sanAfter", 60 - settledLoss,
                            "sanLoss", settledLoss));
            result.setResolvedAt(LocalDateTime.now());
        }
        return result;
    }

    private DiceRollResult insanityResult(
            Long id, String type, DiceRollResultVO resultData, boolean resolved) {
        var resolution = com.me.galchat.domain.vo.DiceResolutionDataVO.pending(
                type,
                301L,
                Map.of(
                        "runId", 5L,
                        "cardId", 11L,
                        "characterName", "林恩",
                        "sanLoss", 6));
        return new DiceRollResult()
                .setId(id)
                .setSummaryId(101L)
                .setCharacterId(null)
                .setRoundNo(3)
                .setDisplayOrder(id.intValue() - 400)
                .setResultData(resultData)
                .setResolutionData(resolution)
                .setResolvedAt(resolved ? LocalDateTime.now() : null);
    }

    private CocCharacterWeapon shotgun(String damage, int remainingAmmo) {
        return new CocCharacterWeapon()
                .setId(81L)
                .setCharacterId(11L)
                .setName("泵动霰弹枪")
                .setSkillName("射击:步枪/霰弹枪")
                .setDamage(damage)
                .setAmmoCapacity(5)
                .setRemainingAmmo(remainingAmmo)
                .setMalfunction("100")
                .setCanImpale(true)
                .setIsBroken(false);
    }

    private CocCharacter damageCard(Long id, String name) {
        return new CocCharacter()
                .setId(id)
                .setRunId(5L)
                .setName(name)
                .setHpCurrent(10)
                .setHpMax(10)
                .setCon(50)
                .setMajorWound(false)
                .setUnconscious(false)
                .setDead(false);
    }

    private KpDiceRequestDTOs.CheckTarget target(String characterName, String checkName) {
        return new KpDiceRequestDTOs.CheckTarget(
                characterName, checkName, CocPercentileModifier.NORMAL);
    }

    @SuppressWarnings("unchecked")
    private List<DiceRollResultCreateDTO> createdDrafts() {
        return (List<DiceRollResultCreateDTO>) org.mockito.Mockito.mockingDetails(internal)
                .getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("createDiceRoll"))
                .findFirst()
                .orElseThrow()
                .getArgument(2);
    }

    private CocDiceCharacterVO player(String name, int checkValue) {
        return card(11L, null, name, checkValue);
    }

    private CocDiceCharacterVO agent(Long participantId, String name, int checkValue) {
        return card(12L, participantId, name, checkValue);
    }

    private CocDiceCharacterVO card(
            Long cardId, Long participantId, String name, int checkValue) {
        return card(cardId, participantId, name, Map.of("侦查", checkValue));
    }

    private CocDiceCharacterVO card(
            Long cardId,
            Long participantId,
            String name,
            Map<String, Integer> checkValues) {
        return cardWithArmor(
                cardId, participantId, name, checkValues, 0);
    }

    private CocDiceCharacterVO cardWithArmor(
            Long cardId,
            Long participantId,
            String name,
            Map<String, Integer> checkValues,
            int armor) {
        return new CocDiceCharacterVO(
                cardId,
                participantId == null ? "PLAYER" : "BOT",
                participantId,
                name,
                checkValues,
                10,
                10,
                60,
                60,
                50,
                armor,
                false,
                false,
                false,
                false,
                false,
                null,
                null);
    }

    private CocDiceCharacterVO cardInCover(
            Long cardId,
            Long participantId,
            String name,
            Map<String, Integer> checkValues) {
        return new CocDiceCharacterVO(
                cardId,
                participantId == null ? "PLAYER" : "BOT",
                participantId,
                name,
                checkValues,
                10,
                10,
                60,
                60,
                50,
                0,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                true,
                false,
                0,
                null,
                false);
    }

    private CocDiceCharacterVO cardWithBuild(
            Long cardId,
            Long participantId,
            String name,
            Map<String, Integer> checkValues,
            int build) {
        return new CocDiceCharacterVO(
                cardId,
                participantId == null ? "PLAYER" : "BOT",
                participantId,
                name,
                checkValues,
                10,
                10,
                60,
                60,
                50,
                build,
                0,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                false,
                false,
                0,
                null,
                false);
    }
}
