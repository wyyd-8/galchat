package com.me.galchat.service.impl.dice;

import com.me.galchat.service.impl.trpg.TrpgCombatLifecycleService;
import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.constant.CocCheckDifficulty;
import com.me.galchat.constant.CocCheckOutcome;
import com.me.galchat.constant.CocPercentileModifier;
import com.me.galchat.constant.CocWeaponCatalogConstant;
import com.me.galchat.constant.DiceRollConstant;
import com.me.galchat.constant.HealingSourceMode;
import com.me.galchat.constant.HealingMode;
import com.me.galchat.constant.MeleeDefenseMode;
import com.me.galchat.constant.GroupCheckRule;
import com.me.galchat.domain.dto.DiceRollResultCreateDTO;
import com.me.galchat.domain.dto.KpDiceRequestDTOs;
import com.me.galchat.domain.dto.KpFirearmRequestDTOs;
import com.me.galchat.domain.dto.KpMeleeRequestDTOs;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.DiceRollResult;
import com.me.galchat.domain.po.DiceRollSummary;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.domain.vo.DiceResolutionDataVO;
import com.me.galchat.domain.vo.DiceRollAggregate;
import com.me.galchat.domain.vo.DiceRollDetailVO;
import com.me.galchat.domain.vo.DiceRollProgressVO;
import com.me.galchat.domain.vo.DiceRollResultVO;
import com.me.galchat.domain.vo.DiceRollSummaryVO;
import com.me.galchat.domain.vo.KpDiceToolResult;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.DiceFollowUpLocator;
import com.me.galchat.service.DiceMessageRoundAppender;
import com.me.galchat.service.DiceRandomSource;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.ICocDiceOrchestrationService;
import com.me.galchat.service.IDiceRollInternalService;
import com.me.galchat.utils.DiceUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CocDiceOrchestrationService implements ICocDiceOrchestrationService {

    private final IDiceRollInternalService internalService;
    private final ICharacterCardService characterCardService;
    private final GroupConversationService conversationService;
    private final DiceFollowUpLocator followUpLocator;
    private final CocDiceSummaryFormatter summaryFormatter;
    private final DiceRandomSource randomSource;
    private final DiceMessageRoundAppender messageRoundAppender;
    private final TrpgCombatLifecycleService combatLifecycleService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Check request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        CocCheckDifficulty difficulty = Objects.requireNonNullElse(
                request.difficulty(), CocCheckDifficulty.REGULAR);
        List<KpDiceRequestDTOs.CheckTarget> targets =
                requireTargets(List.of(request.target()), false);
        return createChecks(
                conversationId, runId, request.reason(), difficulty, targets, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestGroupCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.GroupCheck request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        CocCheckDifficulty difficulty = Objects.requireNonNullElse(
                request.difficulty(), CocCheckDifficulty.REGULAR);
        GroupCheckRule groupRule = Objects.requireNonNullElse(
                request.groupRule(), GroupCheckRule.SEPARATE);
        if (request.targets() == null || request.targets().size() < 2) {
            throw new UserRequestException("群体检定至少需要两个角色");
        }
        List<KpDiceRequestDTOs.CheckTarget> targets =
                requireTargets(request.targets(), false);
        return createChecks(
                conversationId, runId, request.reason(), difficulty, targets, groupRule);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestFirearmAttack(
            Long conversationId,
            Long runId,
            KpFirearmRequestDTOs.Attack request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        if (!StringUtils.hasText(request.characterName())
                || !StringUtils.hasText(request.weaponName())
                || request.firingMode() == null) {
            throw new UserRequestException("开火角色、武器和射击方式不能为空");
        }
        if (request.targets() == null || request.targets().isEmpty()) {
            throw new UserRequestException("枪械攻击目标不能为空");
        }
        CocDiceCharacterVO attacker = characterCardService
                .requireDiceCharacter(runId, request.characterName().trim());
        CocCharacterWeapon weapon = characterCardService
                .requireWeaponForUpdate(
                        runId, attacker.name(), request.weaponName().trim());
        if (Boolean.TRUE.equals(weapon.getIsBroken())) {
            throw new UserRequestException("损坏的武器不能射击");
        }
        if (weapon.getRemainingAmmo() == null
                || weapon.getRemainingAmmo() < 1) {
            throw new UserRequestException("武器没有可供发射的弹药");
        }
        Integer skillValue = attacker.checkValues() == null
                ? null : attacker.checkValues().get(weapon.getSkillName());
        if (skillValue == null || skillValue < 1 || skillValue > 100) {
            throw new UserRequestException("人物卡缺少武器对应的射击技能");
        }
        int malfunctionThreshold = parseMalfunction(weapon.getMalfunction());

        int remaining = weapon.getRemainingAmmo();
        int globalGroupIndex = 0;
        int displayOrder = 1;
        int shooterSituationPenaltyDice = 0;
        if (Boolean.TRUE.equals(request.shooterMovingFast())) {
            shooterSituationPenaltyDice++;
        }
        if (Boolean.TRUE.equals(request.firingPostureRestricted())) {
            shooterSituationPenaltyDice++;
        }
        List<DiceRollResultCreateDTO> drafts = new ArrayList<>();
        Set<String> targetNames = new HashSet<>();
        for (KpFirearmRequestDTOs.Target target : request.targets()) {
            if (target == null || !StringUtils.hasText(
                    target.targetCharacterName())
                    || target.bulletCount() < 1) {
                throw new UserRequestException("射击目标和子弹数无效");
            }
            String targetName = target.targetCharacterName().trim();
            if (!targetNames.add(targetName)) {
                throw new UserRequestException("同一目标只能声明一次子弹分配");
            }
            String damageFormula = CocFirearmRules.damageFormulaForDistance(
                    weapon.getDamage(), target.distance());
            CocDiceCharacterVO targetCard = characterCardService
                    .requireDiceCharacter(runId, targetName);
            boolean targetInCover = Boolean.TRUE.equals(
                    targetCard.inCover());
            boolean targetMovingFast = Boolean.TRUE.equals(
                    target.targetMovingFast());
            boolean smallTarget = targetCard.build() != null
                    && targetCard.build() <= -2;
            int automaticSituationPenaltyDice =
                    shooterSituationPenaltyDice
                            + (targetInCover ? 1 : 0)
                            + (targetMovingFast ? 1 : 0)
                            + (smallTarget ? 1 : 0);
            int allocated = Math.min(remaining, target.bulletCount());
            remaining -= allocated;
            for (int groupSize : CocFirearmRules.groupSizes(
                    request.firingMode(), skillValue, allocated)) {
                int groupIndex = globalGroupIndex++;
                CocFirearmRules.AttackAdjustment adjustment =
                        CocFirearmRules.adjustment(
                                request.firingMode(), groupIndex,
                                target.baseModifier(),
                                automaticSituationPenaltyDice);
                if (adjustment.impossible()) {
                    continue;
                }
                List<Map<String, Object>> modifierFactors = kpModifierFactors(
                        target.baseModifier(), target.baseModifierReason());
                if (Boolean.TRUE.equals(request.shooterMovingFast())) {
                    modifierFactors.add(modifierFactor(
                            "BACKEND", "PENALTY", 1,
                            "SHOOTER_MOVING_FAST", "射手正在高速移动"));
                }
                if (Boolean.TRUE.equals(request.firingPostureRestricted())) {
                    modifierFactors.add(modifierFactor(
                            "BACKEND", "PENALTY", 1,
                            "FIRING_POSTURE_RESTRICTED", "射击姿势明显受限"));
                }
                if (targetInCover) {
                    modifierFactors.add(modifierFactor(
                            "BACKEND", "PENALTY", 1,
                            "TARGET_IN_COVER",
                            "目标“" + targetCard.name() + "”处于掩护中"));
                }
                if (targetMovingFast) {
                    modifierFactors.add(modifierFactor(
                            "BACKEND", "PENALTY", 1,
                            "TARGET_MOVING_FAST",
                            "目标“" + targetCard.name() + "”正在高速移动"));
                }
                if (smallTarget) {
                    modifierFactors.add(modifierFactor(
                            "BACKEND", "PENALTY", 1,
                            "SMALL_TARGET",
                            "目标“" + targetCard.name() + "”体型过小"));
                }
                int firingModePenaltyDice = switch (request.firingMode()) {
                    case HANDGUN_MULTIPLE, SEMI_AUTO -> 1;
                    case FULL_AUTO -> groupIndex;
                    default -> 0;
                };
                if (firingModePenaltyDice > 0) {
                    String firingModeReason = switch (request.firingMode()) {
                        case HANDGUN_MULTIPLE -> "本轮使用手枪连射";
                        case SEMI_AUTO -> "本轮使用半自动多次射击";
                        case FULL_AUTO -> "全自动射击进入后续弹组";
                        default -> throw new IllegalStateException("射击模式没有惩罚骰");
                    };
                    modifierFactors.add(modifierFactor(
                            "BACKEND", "PENALTY", firingModePenaltyDice,
                            "FIRING_MODE", firingModeReason));
                }
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("runId", runId);
                rule.put("weaponId", weapon.getId());
                rule.put("attackerCardId", attacker.cardId());
                rule.put("characterName", attacker.name());
                rule.put("weaponName", weapon.getName());
                rule.put("skillName", weapon.getSkillName());
                rule.put("targetValue", skillValue);
                rule.put("targetCardId", targetCard.cardId());
                rule.put("targetCharacterName", targetCard.name());
                rule.put("targetConValue", targetCard.con());
                rule.put("targetArmor", normalizeArmor(targetCard.armor()));
                rule.put("firingMode", request.firingMode().name());
                rule.put("bulletsInGroup", groupSize);
                rule.put("modifier", adjustment.modifier().name());
                rule.put("difficultyIncrease", adjustment.difficultyIncrease());
                rule.put("automaticSituationPenaltyDice",
                        automaticSituationPenaltyDice);
                rule.put("targetInCover", targetInCover);
                rule.put("shooterMovingFast", Boolean.TRUE.equals(
                        request.shooterMovingFast()));
                rule.put("targetMovingFast", targetMovingFast);
                rule.put("firingPostureRestricted", Boolean.TRUE.equals(
                        request.firingPostureRestricted()));
                rule.put("smallTarget", smallTarget);
                rule.put("malfunctionThreshold", malfunctionThreshold);
                rule.put("fumbleBreaksWeapon", request.fumbleBreaksWeapon());
                rule.put("canImpale", Boolean.TRUE.equals(
                        weapon.getCanImpale()));
                rule.put("damageFormula", damageFormula);
                putModifierFactors(rule, modifierFactors);

                DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
                draft.setCharacterId(automaticRoller(attacker));
                draft.setDisplayOrder(displayOrder++);
                draft.setDisplayType(DiceRollConstant.TYPE_FIREARM_ATTACK);
                draft.setReason(request.reason().trim());
                draft.setFormula(adjustment.modifier().formula());
                draft.setResolutionData(DiceResolutionDataVO.pending(
                        DiceRollConstant.TYPE_FIREARM_ATTACK, null, rule));
                drafts.add(draft);
            }
            if (remaining == 0) {
                break;
            }
        }
        if (drafts.isEmpty()) {
            throw new UserRequestException("没有能够进行的枪械攻击检定组");
        }
        weapon.setRemainingAmmo(remaining);
        characterCardService.updateWeapon(weapon);
        return createFirearmAndSettle(
                conversationId, request.reason(), drafts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestMeleeAttack(
            Long conversationId,
            Long runId,
            KpMeleeRequestDTOs.Attack request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        if (request.attacker() == null || request.defender() == null
                || !StringUtils.hasText(request.attacker().characterName())
                || !StringUtils.hasText(request.defender().characterName())
                || request.defender().defenseMode() == null) {
            throw new UserRequestException("近战攻击者、防守者和防守方式不能为空");
        }
        String attackerName = request.attacker().characterName().trim();
        String defenderName = request.defender().characterName().trim();
        if (attackerName.equals(defenderName)) {
            throw new UserRequestException("近战攻击者和防守者不能是同一角色");
        }
        MeleeDefenseMode defenseMode = request.defender().defenseMode();
        if (defenseMode != MeleeDefenseMode.COUNTERATTACK
                && StringUtils.hasText(
                request.defender().counterWeaponName())) {
            throw new UserRequestException("只有反击可以指定反击武器");
        }

        CocDiceCharacterVO attacker = characterCardService
                .requireDiceCharacter(runId, attackerName);
        CocDiceCharacterVO defender = characterCardService
                .requireDiceCharacter(runId, defenderName);
        CocCharacter attackerEntity = requireMeleeCharacter(
                runId, attacker.cardId());
        CocCharacter defenderEntity = requireMeleeCharacter(
                runId, defender.cardId());
        EffectiveMeleeWeapon attackWeapon = effectiveMeleeWeapon(
                runId, attacker.name(), request.attacker().weaponName());
        int attackValue = requireCheckValue(
                attacker, attackWeapon.skillName());
        boolean defenderAlreadyAttacked = Boolean.TRUE.equals(
                defenderEntity.getMeleeAttackedThisRound());
        CocPercentileModifier attackModifier = normalizeModifier(
                request.attacker().modifier());
        List<Map<String, Object>> attackModifierFactors = kpModifierFactors(
                request.attacker().modifier(),
                request.attacker().modifierReason());
        if (defenderAlreadyAttacked) {
            attackModifier = addBonusDie(attackModifier);
            attackModifierFactors.add(modifierFactor(
                    "BACKEND", "BONUS", 1,
                    "DEFENDER_ALREADY_ATTACKED",
                    "防守者本轮已经遭受过近战攻击"));
        }

        List<DiceRollResultCreateDTO> drafts = new ArrayList<>();
        drafts.add(meleeCheckDraft(
                runId,
                attacker,
                defender,
                "ATTACKER",
                attackWeapon,
                attackerEntity.getDamageBonus(),
                attackValue,
                attackModifier,
                attackModifierFactors,
                defenseMode,
                request.reason(),
                1));

        if (defenseMode != MeleeDefenseMode.NONE) {
            EffectiveMeleeWeapon defenseWeapon;
            String checkName;
            if (defenseMode == MeleeDefenseMode.DODGE) {
                defenseWeapon = null;
                checkName = "闪避";
            } else {
                defenseWeapon = effectiveMeleeWeapon(
                        runId, defender.name(),
                        request.defender().counterWeaponName());
                checkName = defenseWeapon.skillName();
            }
            int defenseValue = requireCheckValue(defender, checkName);
            drafts.add(meleeCheckDraft(
                    runId,
                    defender,
                    attacker,
                    "DEFENDER",
                    defenseWeapon,
                    defenderEntity.getDamageBonus(),
                    defenseValue,
                    normalizeModifier(request.defender().modifier()),
                    kpModifierFactors(
                            request.defender().modifier(),
                            request.defender().modifierReason()),
                    defenseMode,
                    request.reason(),
                    2));
        }
        if (!defenderAlreadyAttacked) {
            defenderEntity.setMeleeAttackedThisRound(true)
                    .setUpdatedAt(LocalDateTime.now());
            characterCardService.updateDiceCharacter(defenderEntity);
        }
        return createMeleeAndSettle(
                conversationId, request.reason(), drafts);
    }

    private CocPercentileModifier addBonusDie(
            CocPercentileModifier modifier) {
        return switch (modifier) {
            case BONUS_2 -> CocPercentileModifier.BONUS_2;
            case BONUS_1 -> CocPercentileModifier.BONUS_2;
            case NORMAL -> CocPercentileModifier.BONUS_1;
            case PENALTY_1 -> CocPercentileModifier.NORMAL;
            case PENALTY_2 -> CocPercentileModifier.PENALTY_1;
        };
    }

    private CocCharacter requireMeleeCharacter(Long runId, Long cardId) {
        CocCharacter character = characterCardService.lockDiceCharacter(
                runId, cardId);
        if (character == null || !StringUtils.hasText(
                character.getDamageBonus())) {
            throw new UserRequestException("近战角色缺少伤害加值");
        }
        return character;
    }

    private EffectiveMeleeWeapon effectiveMeleeWeapon(
            Long runId, String characterName, String requestedWeaponName) {
        if (!StringUtils.hasText(requestedWeaponName)) {
            return new EffectiveMeleeWeapon(
                    "徒手", "斗殴", "1D3+DB", false);
        }
        CocCharacterWeapon weapon = characterCardService
                .requireWeaponForUpdate(
                        runId, characterName, requestedWeaponName.trim());
        if (Boolean.TRUE.equals(weapon.getIsBroken())) {
            throw new UserRequestException("损坏的武器不能用于近战攻击");
        }
        if (!StringUtils.hasText(weapon.getSkillName())) {
            throw new UserRequestException("近战武器缺少对应技能");
        }
        if (isRangedWeapon(weapon)) {
            boolean handgun = "射击:手枪".equals(
                    weapon.getSkillName().trim());
            return handgun
                    ? new EffectiveMeleeWeapon(
                    "小型棍棒（枪托替代）", "斗殴",
                    "1D6+DB", false)
                    : new EffectiveMeleeWeapon(
                    "大型棍棒（枪托替代）", "斗殴",
                    "1D8+DB", false);
        }
        if (!StringUtils.hasText(weapon.getDamage())) {
            throw new UserRequestException("近战武器缺少伤害公式");
        }
        CocMeleeRules.damagePlan(
                weapon.getDamage(), "0", false,
                Boolean.TRUE.equals(weapon.getCanImpale()));
        return new EffectiveMeleeWeapon(
                weapon.getName(), weapon.getSkillName().trim(),
                weapon.getDamage().trim(),
                Boolean.TRUE.equals(weapon.getCanImpale()));
    }

    private boolean isRangedWeapon(CocCharacterWeapon weapon) {
        String skillName = weapon.getSkillName().trim();
        if (skillName.startsWith("射击:")
                || "投掷".equals(skillName)) {
            return true;
        }
        return CocWeaponCatalogConstant.findByExactName(weapon.getName())
                .map(definition -> definition.kind()
                        != CocWeaponCatalogConstant.WeaponKind.MELEE)
                .orElse(false);
    }

    private DiceRollResultCreateDTO meleeCheckDraft(
            Long runId,
            CocDiceCharacterVO actor,
            CocDiceCharacterVO opponent,
            String role,
            EffectiveMeleeWeapon weapon,
            String damageBonus,
            int targetValue,
            CocPercentileModifier modifier,
            List<Map<String, Object>> modifierFactors,
            MeleeDefenseMode defenseMode,
            String reason,
            int displayOrder) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("runId", runId);
        rule.put("cardId", actor.cardId());
        rule.put("characterName", actor.name());
        rule.put("role", role);
        rule.put("checkName", weapon == null ? "闪避" : weapon.skillName());
        rule.put("targetValue", targetValue);
        rule.put("modifier", modifier.name());
        putModifierFactors(rule, modifierFactors);
        rule.put("defenseMode", defenseMode.name());
        rule.put("opponentCardId", opponent.cardId());
        rule.put("opponentCharacterName", opponent.name());
        rule.put("opponentConValue", opponent.con());
        rule.put("opponentArmor", normalizeArmor(opponent.armor()));
        if (weapon != null) {
            rule.put("weaponName", weapon.name());
            rule.put("damageFormula", weapon.damage());
            rule.put("damageBonus", damageBonus);
            rule.put("canImpale", weapon.canImpale());
        }
        DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
        draft.setCharacterId(automaticRoller(actor));
        draft.setDisplayOrder(displayOrder);
        draft.setDisplayType(DiceRollConstant.TYPE_MELEE_ATTACK);
        draft.setReason(reason.trim());
        draft.setFormula(modifier.formula());
        draft.setResolutionData(DiceResolutionDataVO.pending(
                DiceRollConstant.TYPE_MELEE_ATTACK, null, rule));
        return draft;
    }

    private KpDiceToolResult createChecks(
            Long conversationId,
            Long runId,
            String reason,
            CocCheckDifficulty difficulty,
            List<KpDiceRequestDTOs.CheckTarget> targets,
            GroupCheckRule groupRule) {
        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            KpDiceRequestDTOs.CheckTarget target = targets.get(index);
            CocDiceCharacterVO card = characterCardService.requireDiceCharacter(
                    runId, target.characterName().trim());
            CheckSelection check = selectHighestCheck(card, target.checkNames());
            CocPercentileModifier modifier = normalizeModifier(target.modifier());
            DiceRollResultCreateDTO draft = checkDraft(
                    card,
                    check.name(),
                    check.value(),
                    difficulty,
                    modifier,
                    kpModifierFactors(modifier, target.modifierReason()),
                    false,
                    DiceRollConstant.TYPE_CHECK,
                    reason,
                    index + 1,
                    null);
            if (groupRule != null) {
                draft.getResolutionData().getRule()
                        .put("groupRule", groupRule.name());
            }
            drafts.add(draft);
        }
        return createAndSettle(conversationId, reason, drafts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestOpposedCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Opposed request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        List<KpDiceRequestDTOs.CheckTarget> targets =
                requireTargets(request.targets(), true);
        Set<String> targetNames = targets.stream()
                .map(target -> target.characterName().trim())
                .collect(java.util.stream.Collectors.toSet());
        String tieWinner = trimToNull(request.tieWinnerCharacterName());
        if (tieWinner != null && !targetNames.contains(tieWinner)) {
            throw new UserRequestException("平局胜者必须属于对抗检定角色");
        }

        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            KpDiceRequestDTOs.CheckTarget target = targets.get(index);
            CocDiceCharacterVO card = characterCardService.requireDiceCharacter(
                    runId, target.characterName().trim());
            CheckSelection check = selectHighestCheck(card, target.checkNames());
            drafts.add(checkDraft(
                    card,
                    check.name(),
                    check.value(),
                    CocCheckDifficulty.REGULAR,
                    normalizeModifier(target.modifier()),
                    kpModifierFactors(
                            target.modifier(), target.modifierReason()),
                    false,
                    DiceRollConstant.TYPE_OPPOSED_CHECK,
                    request.reason(),
                    index + 1,
                    tieWinner));
        }
        return createAndSettle(conversationId, request.reason(), drafts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestPushedCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.Pushed request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        CocCheckDifficulty difficulty = Objects.requireNonNullElse(
                request.difficulty(), CocCheckDifficulty.REGULAR);
        List<KpDiceRequestDTOs.CheckTarget> targets =
                requireTargets(request.targets(), false);
        GroupCheckRule groupRule = targets.size() > 1
                ? Objects.requireNonNullElse(
                        request.groupRule(), GroupCheckRule.SEPARATE)
                : null;
        Long summaryId = request.diceRollSummaryId();
        DiceRollSummary summary = internalService.requireSummaryForUpdate(summaryId);
        requireConversation(summary, conversationId);

        List<DiceRollResult> existing = safeResults(
                internalService.listResultEntities(summaryId));
        List<DiceRollResult> checks = existing.stream()
                .filter(result -> DiceRollConstant.TYPE_CHECK.equals(
                        resolution(result).getType()))
                .toList();
        if (checks.isEmpty()) {
            throw new UserRequestException("指定掷骰不是普通检定");
        }
        int latestRound = checks.stream()
                .map(DiceRollResult::getRoundNo)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElseThrow(() -> new UserRequestException("指定掷骰不是普通检定"));
        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            KpDiceRequestDTOs.CheckTarget target = targets.get(index);
            CocDiceCharacterVO card = characterCardService.requireDiceCharacter(
                    runId, target.characterName().trim());
            CheckSelection check = selectHighestCheck(card, target.checkNames());
            DiceRollResultCreateDTO draft = checkDraft(
                    card,
                    check.name(),
                    check.value(),
                    difficulty,
                    normalizeModifier(target.modifier()),
                    kpModifierFactors(
                            target.modifier(), target.modifierReason()),
                    true,
                    DiceRollConstant.TYPE_CHECK,
                    request.reason(),
                    index + 1,
                    null);
            if (groupRule != null) {
                draft.getResolutionData().getRule()
                        .put("groupRule", groupRule.name());
            }
            drafts.add(draft);
        }

        List<DiceRollResult> created = internalService.appendDiceRollRound(
                conversationId, summaryId, drafts);
        int nextRound = created.stream()
                .map(DiceRollResult::getRoundNo)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(latestRound + 1);
        summary.setRoundCount(Math.max(latestRound, nextRound));
        settleAlreadyRolled(created);
        List<DiceRollResult> allResults = mergeResults(existing, created);
        refreshSummary(summary, allResults);
        return toolResult(summary, created);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestSanCheck(
            Long conversationId, Long runId, KpDiceRequestDTOs.SanCheck request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        List<String> names = requireCharacterNames(request.characterNames());
        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(names.size());
        for (int index = 0; index < names.size(); index++) {
            CocDiceCharacterVO card = characterCardService.requireDiceCharacter(
                    runId, names.get(index));
            if (card.sanCurrent() == null || card.sanCurrent() < 1
                    || card.sanCurrent() > 100) {
                throw new UserRequestException("角色“" + card.name() + "”的当前理智值无法检定");
            }
            drafts.add(checkDraft(
                    card,
                    "理智",
                    card.sanCurrent(),
                    CocCheckDifficulty.REGULAR,
                    CocPercentileModifier.NORMAL,
                    List.of(),
                    false,
                    DiceRollConstant.TYPE_SAN_CHECK,
                    request.reason(),
                    index + 1,
                    null));
        }
        return createAndSettle(conversationId, request.reason(), drafts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult rollSanLoss(
            Long conversationId, Long runId, KpDiceRequestDTOs.SanLoss request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        requireFormula(request.successFormula(), "SAN成功损失公式不能为空");
        requireFormula(request.failureFormula(), "SAN失败损失公式不能为空");

        Long summaryId = followUpLocator.requireLatestSummaryId(
                conversationId, Set.of(DiceRollConstant.TOOL_REQUEST_SAN_CHECK));
        DiceRollSummary summary = internalService.requireSummaryForUpdate(summaryId);
        requireConversation(summary, conversationId);
        if (!DiceRollConstant.STATUS_COMPLETED.equals(summary.getStatus())) {
            throw new UserRequestException("前一轮理智检定尚未完成");
        }
        List<DiceRollResult> existing = safeResults(
                internalService.listResultEntities(summaryId));
        int previousRound = summary.getRoundCount();
        List<DiceRollResult> sanChecks = existing.stream()
                .filter(result -> Objects.equals(previousRound, result.getRoundNo()))
                .filter(result -> DiceRollConstant.TYPE_SAN_CHECK.equals(
                        resolution(result).getType()))
                .toList();
        if (sanChecks.isEmpty()) {
            throw new UserRequestException("前一轮不是理智检定");
        }

        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(sanChecks.size());
        for (int index = 0; index < sanChecks.size(); index++) {
            DiceRollResult sanCheck = sanChecks.get(index);
            CocCheckOutcome checkOutcome = CocCheckOutcome.valueOf(
                    outcomeString(sanCheck, "category"));
            String selectedFormula = CocDiceRules.selectSanLossFormula(
                    checkOutcome,
                    request.successFormula().trim(),
                    request.failureFormula().trim());
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("runId", runId);
            rule.put("cardId", longValue(
                    resolution(sanCheck).getRule(), "cardId"));
            rule.put("characterName", ruleString(sanCheck, "characterName"));
            rule.put("sanCheckOutcome", checkOutcome.name());
            rule.put("successFormula", request.successFormula().trim());
            rule.put("failureFormula", request.failureFormula().trim());

            DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
            draft.setCharacterId(sanCheck.getCharacterId());
            draft.setDisplayOrder(index + 1);
            draft.setDisplayType(DiceRollConstant.TYPE_SAN_LOSS);
            draft.setReason(request.reason().trim());
            draft.setFormula(selectedFormula);
            draft.setResolutionData(DiceResolutionDataVO.pending(
                    DiceRollConstant.TYPE_SAN_LOSS, sanCheck.getId(), rule));
            drafts.add(draft);
        }

        List<DiceRollResult> created = internalService.appendDiceRollRound(
                conversationId, summaryId, drafts);
        updateRoundCountFromCreated(summary, created, previousRound + 1);
        settleAlreadyRolled(created);
        List<DiceRollResult> allResults = mergeResults(existing, created);
        settleCompletedInsanityPairs(allResults);
        refreshSummary(summary, allResults);
        List<DiceRollResult> insanityResults =
                appendTemporaryInsanityRoundIfNeeded(summary, allResults);
        List<DiceRollResult> returned = new ArrayList<>(created);
        returned.addAll(insanityResults);
        return toolResult(summary, returned);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult rollDamage(
            Long conversationId, Long runId, KpDiceRequestDTOs.Damage request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        List<KpDiceRequestDTOs.DamageTarget> targets =
                requireDamageTargets(request.targets());

        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(
                targets.size() * 2);
        int displayOrder = 1;
        for (int index = 0; index < targets.size(); index++) {
            KpDiceRequestDTOs.DamageTarget target = targets.get(index);
            CocDamageRules.DamageExpression damage =
                    requireDamageExpression(target.formula());
            if (damage.hpFormula() != null) {
                requireFormula(damage.hpFormula(), "伤害公式不能为空");
            }
            CocDiceCharacterVO card = characterCardService.requireDiceCharacter(
                    runId, target.targetCharacterName().trim());
            if (damage.hpFormula() != null
                    && (card.con() == null
                    || card.con() < 1 || card.con() > 100)) {
                throw new UserRequestException(
                        "角色“" + card.name() + "”的CON值无效");
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("conversationId", conversationId);
            rule.put("runId", runId);
            rule.put("cardId", card.cardId());
            rule.put("characterName", card.name());
            rule.put("rollBundleKey", "damage:" + index + ":" + card.cardId());
            if (damage.hpFormula() != null) {
                rule.put("conValue", card.con());
                drafts.add(damageDraft(
                        card.participantId(), displayOrder++,
                        request.reason().trim(), damage.hpFormula(),
                        null, rule));
            }
            if (damage.stun()) {
                drafts.add(stunDraft(
                        card.participantId(), displayOrder++,
                        request.reason().trim(), null, rule));
            }
        }

        DiceRollAggregate aggregate = internalService.createDiceRoll(
                conversationId, request.reason().trim(), drafts);
        DiceRollSummary summary = aggregate.summary();
        List<DiceRollResult> damageResults = aggregate.results();
        settleAlreadyRolled(damageResults);
        refreshSummary(summary, damageResults);
        List<DiceRollResult> conResults =
                appendMajorWoundConRoundIfNeeded(summary, damageResults);
        List<DiceRollResult> returned = new ArrayList<>(damageResults);
        returned.addAll(conResults);
        return toolResult(summary, returned);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult rollHealing(
            Long conversationId, Long runId, KpDiceRequestDTOs.Healing request) {
        requireContext(conversationId, runId);
        requireRequest(request, request == null ? null : request.reason());
        if (request.sourceMode() == null) {
            throw new UserRequestException("回血来源模式不能为空");
        }
        if (request.mode() == null) {
            throw new UserRequestException("恢复方式不能为空");
        }
        HealingSourceMode sourceMode = request.sourceMode();
        List<KpDiceRequestDTOs.HealingTarget> targets =
                requireHealingTargets(request.targets(), sourceMode);

        DiceRollSummary summary;
        List<DiceRollResult> existing;
        if (sourceMode == HealingSourceMode.FOLLOW_UP) {
            Long summaryId = followUpLocator.requireLatestSummaryId(
                    conversationId,
                    Set.of(
                            DiceRollConstant.TOOL_REQUEST_CHECK,
                            DiceRollConstant.TOOL_REQUEST_GROUP_CHECK,
                            DiceRollConstant.TOOL_REQUEST_PUSHED_CHECK));
            summary = internalService.requireSummaryForUpdate(summaryId);
            requireConversation(summary, conversationId);
            if (!DiceRollConstant.STATUS_COMPLETED.equals(summary.getStatus())) {
                throw new UserRequestException("前置检定尚未完成");
            }
            existing = safeResults(internalService.listResultEntities(summaryId));
        } else {
            summary = null;
            existing = List.of();
        }

        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            KpDiceRequestDTOs.HealingTarget target = targets.get(index);
            requireFormula(target.formula(), "回血公式不能为空");
            DiceRollResult source = sourceMode == HealingSourceMode.FOLLOW_UP
                    ? requireSuccessfulHealingSource(
                    existing, target.sourceCharacterName().trim())
                    : null;
            CocDiceCharacterVO card = characterCardService.requireDiceCharacter(
                    runId, target.targetCharacterName().trim());

            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("runId", runId);
            rule.put("cardId", card.cardId());
            rule.put("characterName", card.name());
            rule.put("mode", request.mode().name());
            rule.put("sourceCharacterName", trimToNull(
                    target.sourceCharacterName()));

            DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
            draft.setCharacterId(card.participantId());
            draft.setDisplayOrder(index + 1);
            draft.setDisplayType(DiceRollConstant.TYPE_HEALING);
            draft.setReason(request.reason().trim());
            draft.setFormula(target.formula().trim());
            draft.setResolutionData(DiceResolutionDataVO.pending(
                    DiceRollConstant.TYPE_HEALING,
                    source == null ? null : source.getId(),
                    rule));
            drafts.add(draft);
        }

        List<DiceRollResult> healingResults;
        if (sourceMode == HealingSourceMode.STANDALONE) {
            DiceRollAggregate aggregate = internalService.createDiceRoll(
                    conversationId, request.reason().trim(), drafts);
            summary = aggregate.summary();
            healingResults = aggregate.results();
        } else {
            int fallbackRound = summary.getRoundCount() + 1;
            healingResults = internalService.appendDiceRollRound(
                    conversationId, summary.getId(), drafts);
            updateRoundCountFromCreated(summary, healingResults, fallbackRound);
        }
        settleAlreadyRolled(healingResults);
        refreshSummary(summary, mergeResults(existing, healingResults));
        return toolResult(summary, healingResults);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KpDiceToolResult requestUnconsciousRecovery(
            Long conversationId, Long runId, Long cardId) {
        requireContext(conversationId, runId);
        CocCharacter card = characterCardService.lockDiceCharacter(
                runId, cardId);
        if (card == null || !Boolean.TRUE.equals(card.getUnconscious())
                || Boolean.TRUE.equals(card.getDying())
                || Boolean.TRUE.equals(card.getDead())) {
            throw new UserRequestException("角色当前不能进行昏迷恢复检定");
        }
        if (card.getCon() == null || card.getCon() < 1
                || card.getCon() > 100) {
            throw new UserRequestException("角色卡CON值无效");
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("runId", runId);
        rule.put("cardId", card.getId());
        rule.put("characterName", card.getName());
        rule.put("targetValue", card.getCon());

        DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
        Long automaticRoller = switch (Objects.toString(
                card.getActorType(), "")) {
            case "PLAYER" -> null;
            case "BOT" -> Objects.requireNonNullElse(
                    card.getParticipantId(), card.getId());
            case "NPC" -> card.getId();
            default -> throw new UserRequestException(
                    "不支持的人物卡控制类型");
        };
        draft.setCharacterId(automaticRoller);
        draft.setDisplayOrder(1);
        draft.setDisplayType(
                DiceRollConstant.TYPE_UNCONSCIOUS_RECOVERY_CON);
        draft.setReason(card.getName() + "尝试脱离昏迷");
        draft.setFormula("1D100");
        draft.setResolutionData(DiceResolutionDataVO.pending(
                DiceRollConstant.TYPE_UNCONSCIOUS_RECOVERY_CON,
                null, rule));
        return createAndSettle(
                conversationId, draft.getReason(), List.of(draft));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DiceRollProgressVO rollPlayerResult(Long resultId) {
        DiceRollResult initial = internalService.requireResult(resultId);
        DiceRollSummary summary = internalService.requireSummaryForUpdate(initial.getSummaryId());
        conversationService.requireActive(summary.getConversationId());
        DiceRollResult result = internalService.requireResult(resultId);
        if (!summary.getId().equals(result.getSummaryId())) {
            throw new UserRequestException("掷骰结果不存在");
        }
        if (result.getCharacterId() != null) {
            throw new UserRequestException("该结果不是玩家掷骰位置");
        }
        if (result.getResolvedAt() != null) {
            return new DiceRollProgressVO(
                    DiceRollSummaryVO.from(summary),
                    DiceRollDetailVO.from(result),
                    List.of());
        }
        if (!Objects.equals(summary.getRoundCount(), result.getRoundNo())) {
            throw new UserRequestException("该结果不属于当前掷骰轮次");
        }
        List<DiceRollResult> storedResults = safeResults(
                internalService.listResultEntities(summary.getId()));
        List<DiceRollResult> rollBundle = pendingPlayerRollBundle(
                result, storedResults);
        for (DiceRollResult pending : rollBundle) {
            DiceRollResultVO placeholder = requirePendingPlayerPlaceholder(
                    pending, summary.getRoundCount());
            pending.setResultData(DiceUtils.roll(placeholder.getFormula()));
        }
        settleAlreadyRolled(rollBundle);
        List<DiceRollResult> allResults = mergeResults(
                storedResults, rollBundle);
        settleCompletedInsanityPairs(allResults);
        refreshSummary(summary, allResults);
        List<DiceRollResult> created = new ArrayList<>(
                appendFirearmDamageRoundIfReady(summary, allResults));
        List<DiceRollResult> afterFirearm = created.isEmpty()
                ? allResults : mergeResults(allResults, created);
        List<DiceRollResult> melee = appendMeleeDamageRoundIfReady(
                summary, afterFirearm);
        created.addAll(melee);
        List<DiceRollResult> afterMelee = melee.isEmpty()
                ? afterFirearm : mergeResults(afterFirearm, melee);
        List<DiceRollResult> insanity = appendTemporaryInsanityRoundIfNeeded(
                summary, afterMelee);
        created.addAll(insanity);
        List<DiceRollResult> currentResults = created.isEmpty()
                ? allResults
                : mergeResults(allResults, created);
        created.addAll(appendMajorWoundConRoundIfNeeded(
                summary, currentResults));
        List<Integer> createdRounds = created.stream()
                .map(DiceRollResult::getRoundNo)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (!createdRounds.isEmpty()) {
            messageRoundAppender.appendRounds(
                    summary.getConversationId(), summary.getId(), createdRounds);
        }
        return new DiceRollProgressVO(
                DiceRollSummaryVO.from(summary),
                DiceRollDetailVO.from(result),
                created.stream().map(DiceRollDetailVO::from).toList());
    }

    private List<DiceRollResult> pendingPlayerRollBundle(
            DiceRollResult requested,
            List<DiceRollResult> storedResults) {
        Object savedBundleKey = resolution(requested).getRule()
                .get("rollBundleKey");
        String bundleKey = savedBundleKey instanceof String value
                && !value.isBlank() ? value : null;
        List<DiceRollResult> bundled = safeResults(storedResults).stream()
                .filter(candidate -> candidate.getCharacterId() == null)
                .filter(candidate -> candidate.getResolvedAt() == null)
                .filter(candidate -> Objects.equals(
                        requested.getRoundNo(), candidate.getRoundNo()))
                .filter(candidate -> {
                    Object candidateBundleKey = resolution(candidate)
                            .getRule().get("rollBundleKey");
                    if (bundleKey != null) {
                        return bundleKey.equals(candidateBundleKey);
                    }
                    return !(candidateBundleKey instanceof String candidateKey)
                            || candidateKey.isBlank();
                })
                .map(candidate -> Objects.equals(
                        requested.getId(), candidate.getId())
                        ? requested : candidate)
                .sorted(Comparator.comparing(
                        DiceRollResult::getDisplayOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (bundled.stream().noneMatch(candidate -> Objects.equals(
                requested.getId(), candidate.getId()))) {
            throw new UserRequestException("同次掷骰数据不完整");
        }
        return bundled;
    }

    private DiceRollResultVO requirePendingPlayerPlaceholder(
            DiceRollResult result,
            Integer currentRound) {
        if (!Objects.equals(currentRound, result.getRoundNo())) {
            throw new UserRequestException("该结果不属于当前掷骰轮次");
        }
        DiceRollResultVO placeholder = result.getResultData();
        if (placeholder == null
                || !StringUtils.hasText(placeholder.getFormula())
                || placeholder.getResult() != null
                || placeholder.getModules() == null
                || placeholder.getModules().isEmpty()) {
            throw new UserRequestException("该位置不是待完成的玩家掷骰");
        }
        return placeholder;
    }

    private KpDiceToolResult createAndSettle(
            Long conversationId, String reason, List<DiceRollResultCreateDTO> drafts) {
        DiceRollAggregate aggregate = internalService.createDiceRoll(
                conversationId, reason.trim(), drafts);
        settleAlreadyRolled(aggregate.results());
        List<DiceRollResult> allResults = mergeResults(
                internalService.listResultEntities(aggregate.summary().getId()),
                aggregate.results());
        refreshSummary(aggregate.summary(), allResults);
        return toolResult(aggregate.summary(), aggregate.results());
    }

    private DiceRollResultCreateDTO damageDraft(
            Long rollerId,
            int displayOrder,
            String reason,
            String formula,
            Long sourceResultId,
            Map<String, Object> rule) {
        DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
        draft.setCharacterId(rollerId);
        draft.setDisplayOrder(displayOrder);
        draft.setDisplayType(DiceRollConstant.TYPE_DAMAGE);
        draft.setReason(reason);
        draft.setFormula(formula);
        draft.setResolutionData(DiceResolutionDataVO.pending(
                DiceRollConstant.TYPE_DAMAGE, sourceResultId,
                new LinkedHashMap<>(rule)));
        return draft;
    }

    private DiceRollResultCreateDTO stunDraft(
            Long rollerId,
            int displayOrder,
            String reason,
            Long sourceResultId,
            Map<String, Object> rule) {
        DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
        draft.setCharacterId(rollerId);
        draft.setDisplayOrder(displayOrder);
        draft.setDisplayType(DiceRollConstant.TYPE_STUN_DURATION);
        draft.setReason(reason);
        draft.setFormula("1D6");
        draft.setResolutionData(DiceResolutionDataVO.pending(
                DiceRollConstant.TYPE_STUN_DURATION, sourceResultId,
                new LinkedHashMap<>(rule)));
        return draft;
    }

    private KpDiceToolResult createFirearmAndSettle(
            Long conversationId,
            String reason,
            List<DiceRollResultCreateDTO> drafts) {
        DiceRollAggregate aggregate = internalService.createDiceRoll(
                conversationId, reason.trim(), drafts);
        settleAlreadyRolled(aggregate.results());
        List<DiceRollResult> allResults = mergeResults(
                internalService.listResultEntities(aggregate.summary().getId()),
                aggregate.results());
        refreshSummary(aggregate.summary(), allResults);
        List<DiceRollResult> damage = appendFirearmDamageRoundIfReady(
                aggregate.summary(), allResults);
        List<DiceRollResult> combined = damage.isEmpty()
                ? allResults : mergeResults(allResults, damage);
        List<DiceRollResult> con = appendMajorWoundConRoundIfNeeded(
                aggregate.summary(), combined);
        List<DiceRollResult> returned = new ArrayList<>(aggregate.results());
        returned.addAll(damage);
        returned.addAll(con);
        return toolResult(aggregate.summary(), returned);
    }

    private KpDiceToolResult createMeleeAndSettle(
            Long conversationId,
            String reason,
            List<DiceRollResultCreateDTO> drafts) {
        DiceRollAggregate aggregate = internalService.createDiceRoll(
                conversationId, reason.trim(), drafts);
        settleAlreadyRolled(aggregate.results());
        List<DiceRollResult> allResults = mergeResults(
                internalService.listResultEntities(aggregate.summary().getId()),
                aggregate.results());
        refreshSummary(aggregate.summary(), allResults);
        List<DiceRollResult> damage = appendMeleeDamageRoundIfReady(
                aggregate.summary(), allResults);
        List<DiceRollResult> combined = damage.isEmpty()
                ? allResults : mergeResults(allResults, damage);
        List<DiceRollResult> con = appendMajorWoundConRoundIfNeeded(
                aggregate.summary(), combined);
        List<DiceRollResult> returned = new ArrayList<>(aggregate.results());
        returned.addAll(damage);
        returned.addAll(con);
        return toolResult(aggregate.summary(), returned);
    }

    private void settleAlreadyRolled(List<DiceRollResult> results) {
        List<DiceRollResult> rolled = safeResults(results).stream()
                .filter(result -> result.getResolvedAt() == null)
                .filter(result -> result.getResultData() != null
                        && result.getResultData().getResult() != null)
                .toList();
        List<DiceRollResult> hpChanges = rolled.stream()
                .filter(result -> {
                    String type = resolution(result).getType();
                    return DiceRollConstant.TYPE_DAMAGE.equals(type)
                            || DiceRollConstant.TYPE_HEALING.equals(type);
                })
                .sorted(Comparator.comparing(result ->
                        longValue(resolution(result).getRule(), "cardId")))
                .toList();
        for (DiceRollResult result : hpChanges) {
            settleResult(result);
        }
        for (DiceRollResult result : rolled) {
            String type = resolution(result).getType();
            if (DiceRollConstant.TYPE_DAMAGE.equals(type)
                    || DiceRollConstant.TYPE_HEALING.equals(type)) {
                continue;
            }
            settleResult(result);
        }
    }

    private void settleResult(DiceRollResult result) {
        DiceResolutionDataVO resolution = resolution(result);
        String type = resolution.getType();
        if (DiceRollConstant.TYPE_CHECK.equals(type)
                || DiceRollConstant.TYPE_OPPOSED_CHECK.equals(type)
                || DiceRollConstant.TYPE_SAN_CHECK.equals(type)) {
            settleCheckResult(result, resolution);
        } else if (DiceRollConstant.TYPE_SAN_LOSS.equals(type)) {
            settleSanLossResult(result, resolution);
        } else if (DiceRollConstant.TYPE_TEMPORARY_INSANITY_TYPE.equals(type)
                || DiceRollConstant.TYPE_TEMPORARY_INSANITY_DURATION.equals(type)) {
            settleInsanityDie(result, resolution);
        } else if (DiceRollConstant.TYPE_DAMAGE.equals(type)) {
            settleDamageResult(result, resolution);
        } else if (DiceRollConstant.TYPE_STUN_DURATION.equals(type)) {
            settleStunDurationResult(result, resolution);
        } else if (DiceRollConstant.TYPE_HEALING.equals(type)) {
            settleHealingResult(result, resolution);
        } else if (DiceRollConstant.TYPE_MAJOR_WOUND_CON.equals(type)) {
            settleMajorWoundConResult(result, resolution);
        } else if (DiceRollConstant.TYPE_UNCONSCIOUS_RECOVERY_CON.equals(
                type)) {
            settleUnconsciousRecoveryResult(result, resolution);
        } else if (DiceRollConstant.TYPE_FIREARM_ATTACK.equals(type)) {
            settleFirearmAttackResult(result, resolution);
        } else if (DiceRollConstant.TYPE_MELEE_ATTACK.equals(type)) {
            settleMeleeAttackResult(result, resolution);
        } else {
            throw new UserRequestException("暂不支持该掷骰结算类型：" + type);
        }
        LocalDateTime now = LocalDateTime.now();
        result.setResolvedAt(now).setUpdatedAt(now);
        internalService.saveResult(result);
    }

    private void settleCheckResult(
            DiceRollResult result, DiceResolutionDataVO resolution) {
        Map<String, Object> rule = resolution.getRule();
        CocDiceRules.CheckResolution check = CocDiceRules.resolveCheck(
                requireRoll(result),
                intValue(rule, "targetValue"),
                CocCheckDifficulty.valueOf(stringValue(rule, "difficulty")));
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("characterName", stringValue(rule, "characterName"));
        outcome.put("checkName", stringValue(rule, "checkName"));
        outcome.put("category", check.outcome().name());
        outcome.put("rank", check.rank().name());
        resolution.setOutcome(outcome);
    }

    private void settleFirearmAttackResult(
            DiceRollResult result, DiceResolutionDataVO resolution) {
        Map<String, Object> rule = resolution.getRule();
        int difficultyIncrease = intValue(rule, "difficultyIncrease");
        CocDiceRules.CheckResolution check = CocDiceRules.resolveCheck(
                requireRoll(result),
                intValue(rule, "targetValue"),
                difficultyIncrease == 0
                        ? CocCheckDifficulty.REGULAR
                        : difficultyIncrease == 1
                        ? CocCheckDifficulty.HARD
                        : CocCheckDifficulty.EXTREME);
        CocCheckOutcome category = check.outcome();
        if (difficultyIncrease >= 3 && requireRoll(result) != 1) {
            category = requireRoll(result) >= 96
                    ? CocCheckOutcome.FUMBLE
                    : CocCheckOutcome.FAILURE;
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("characterName", stringValue(rule, "characterName"));
        outcome.put("targetCharacterName",
                stringValue(rule, "targetCharacterName"));
        outcome.put("category", category.name());
        outcome.put("rank", check.rank().name());
        outcome.put("valid", true);
        resolution.setOutcome(outcome);
    }

    private void settleMeleeAttackResult(
            DiceRollResult result, DiceResolutionDataVO resolution) {
        Map<String, Object> rule = resolution.getRule();
        CocDiceRules.CheckResolution check = CocDiceRules.resolveCheck(
                requireRoll(result),
                intValue(rule, "targetValue"),
                CocCheckDifficulty.REGULAR);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("characterName", stringValue(rule, "characterName"));
        outcome.put("role", stringValue(rule, "role"));
        outcome.put("category", check.outcome().name());
        outcome.put("rank", check.rank().name());
        resolution.setOutcome(outcome);
    }

    private void settleSanLossResult(
            DiceRollResult result, DiceResolutionDataVO resolution) {
        int rolledLoss = requireRoll(result);
        if (rolledLoss < 0) {
            throw new UserRequestException("理智损失不能为负数");
        }
        Map<String, Object> rule = resolution.getRule();
        Long runId = longValue(rule, "runId");
        Long cardId = longValue(rule, "cardId");
        CocCharacter card = characterCardService.lockDiceCharacter(runId, cardId);
        if (card == null || card.getSanCurrent() == null) {
            throw new UserRequestException("角色卡理智值不存在");
        }
        int sanBefore = card.getSanCurrent();
        int actualLoss = Math.min(sanBefore, rolledLoss);
        int sanAfter = sanBefore - actualLoss;
        card.setSanCurrent(sanAfter).setUpdatedAt(LocalDateTime.now());
        characterCardService.updateDiceCharacter(card);

        String characterName = stringValue(rule, "characterName");
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("characterName", characterName);
        outcome.put("sanLoss", actualLoss);
        resolution.setOutcome(outcome);
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("sanBefore", sanBefore);
        effect.put("sanAfter", sanAfter);
        effect.put("sanLoss", actualLoss);
        resolution.setEffect(effect);
    }

    private void settleInsanityDie(
            DiceRollResult result, DiceResolutionDataVO resolution) {
        int roll = requireRoll(result);
        if (roll < 1 || roll > 10) {
            throw new UserRequestException("临时疯狂骰必须为1到10");
        }
        String characterName = stringValue(
                resolution.getRule(), "characterName");
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("characterName", characterName);
        if (DiceRollConstant.TYPE_TEMPORARY_INSANITY_TYPE.equals(
                resolution.getType())) {
            outcome.put("typeRoll", roll);
        } else {
            outcome.put("durationHours", roll);
        }
        resolution.setOutcome(outcome);
    }

    private void settleDamageResult(
            DiceRollResult result, DiceResolutionDataVO resolution) {
        int rawDamage = requireRoll(result);
        if (Boolean.TRUE.equals(resolution.getRule().get("melee"))) {
            rawDamage = Math.max(0, rawDamage);
        }
        if (rawDamage < 0) {
            throw new UserRequestException("伤害不能为负数");
        }
        Map<String, Object> rule = resolution.getRule();
        CocCharacter card = characterCardService.lockDiceCharacter(
                longValue(rule, "runId"), longValue(rule, "cardId"));
        if (card == null || Boolean.TRUE.equals(card.getDead())) {
            throw new UserRequestException("死亡角色不能再受到伤害");
        }
        if (card.getHpCurrent() == null || card.getHpMax() == null) {
            throw new UserRequestException("角色卡生命值不存在");
        }
        CocDiceRules.DamageResolution damage = CocDiceRules.resolveDamage(
                rawDamage, card.getHpCurrent(), card.getHpMax());
        boolean majorBefore = Boolean.TRUE.equals(card.getMajorWound());
        boolean unconsciousBefore = Boolean.TRUE.equals(card.getUnconscious());
        boolean dyingBefore = Boolean.TRUE.equals(card.getDying());
        boolean deadBefore = Boolean.TRUE.equals(card.getDead());
        boolean majorAfter = majorBefore || damage.majorWound();
        boolean deadAfter = deadBefore || rawDamage > card.getHpMax();
        boolean dyingAfter = !deadAfter && (dyingBefore
                || damage.hpAfter() == 0 && majorAfter);
        boolean unconsciousAfter = unconsciousBefore
                || damage.hpAfter() == 0 || dyingAfter || deadAfter;
        card.setHpCurrent(damage.hpAfter())
                .setMajorWound(majorAfter)
                .setUnconscious(unconsciousAfter)
                .setDying(dyingAfter)
                .setDead(deadAfter)
                .setUpdatedAt(LocalDateTime.now());
        characterCardService.updateDiceCharacter(card);
        if ((!dyingBefore && dyingAfter) || (!deadBefore && deadAfter)) {
            Object savedConversationId = rule.get("conversationId");
            Long conversationId = savedConversationId instanceof Number number
                    ? number.longValue()
                    : longValue(rule, "runId");
            combatLifecycleService.forfeitCurrentRoundSlot(
                    conversationId, card.getId());
        }

        String characterName = stringValue(rule, "characterName");
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("characterName", characterName);
        outcome.put("rawDamage", rawDamage);
        outcome.put("majorWound", majorAfter);
        resolution.setOutcome(outcome);
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("hpBefore", damage.hpBefore());
        effect.put("hpAfter", damage.hpAfter());
        effect.put("hpLoss", damage.hpBefore() - damage.hpAfter());
        effect.put("majorWoundBefore", majorBefore);
        effect.put("majorWound", majorAfter);
        effect.put("majorWoundChanged", !majorBefore && majorAfter);
        effect.put("unconsciousBefore", unconsciousBefore);
        effect.put("unconscious", unconsciousAfter);
        effect.put("dyingBefore", dyingBefore);
        effect.put("dying", dyingAfter);
        effect.put("deadBefore", deadBefore);
        effect.put("dead", deadAfter);
        resolution.setEffect(effect);
    }

    private void settleStunDurationResult(
            DiceRollResult result, DiceResolutionDataVO resolution) {
        int rolledDuration = requireRoll(result);
        if (rolledDuration < 1 || rolledDuration > 6) {
            throw new UserRequestException("眩晕持续时间骰必须为1到6");
        }
        Map<String, Object> rule = resolution.getRule();
        CocCharacter card = characterCardService.lockDiceCharacter(
                longValue(rule, "runId"), longValue(rule, "cardId"));
        if (card == null) {
            throw new UserRequestException("承受眩晕的角色卡不存在");
        }
        int before = Math.max(0, Objects.requireNonNullElse(
                card.getStunnedRemainingRounds(), 0));
        int after = Math.max(before, rolledDuration);
        if (after != before) {
            card.setStunnedRemainingRounds(after)
                    .setUpdatedAt(LocalDateTime.now());
            characterCardService.updateDiceCharacter(card);
        }

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("characterName", stringValue(rule, "characterName"));
        outcome.put("rolledDuration", rolledDuration);
        resolution.setOutcome(outcome);
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("stunBefore", before);
        effect.put("rolledDuration", rolledDuration);
        effect.put("stunAfter", after);
        effect.put("stunChanged", after != before);
        resolution.setEffect(effect);
    }

    private void settleHealingResult(
            DiceRollResult result, DiceResolutionDataVO resolution) {
        int rawHealing = requireRoll(result);
        if (rawHealing < 0) {
            throw new UserRequestException("回血不能为负数");
        }
        Map<String, Object> rule = resolution.getRule();
        CocCharacter card = characterCardService.lockDiceCharacter(
                longValue(rule, "runId"), longValue(rule, "cardId"));
        if (card == null || Boolean.TRUE.equals(card.getDead())) {
            throw new UserRequestException("死亡角色不能恢复生命");
        }
        if (card.getHpCurrent() == null || card.getHpMax() == null
                || card.getHpMax() < 1 || card.getHpCurrent() < 0
                || card.getHpCurrent() > card.getHpMax()) {
            throw new UserRequestException("角色卡生命值不存在或范围无效");
        }
        int hpBefore = card.getHpCurrent();
        int hpAfter = Math.min(card.getHpMax(), hpBefore + rawHealing);
        int hpGain = hpAfter - hpBefore;
        HealingMode mode = HealingMode.valueOf(stringValue(rule, "mode"));
        boolean majorWoundBefore = Boolean.TRUE.equals(card.getMajorWound());
        boolean unconsciousBefore = Boolean.TRUE.equals(card.getUnconscious());
        boolean dyingBefore = Boolean.TRUE.equals(card.getDying());
        boolean majorWoundAfter = switch (mode) {
            case FIRST_AID, MEDICINE -> false;
            case OTHER -> majorWoundBefore;
        };
        boolean unconsciousAfter = mode == HealingMode.FIRST_AID
                ? false : unconsciousBefore;
        boolean dyingAfter = dyingBefore && hpAfter == 0;
        card.setHpCurrent(hpAfter)
                .setMajorWound(majorWoundAfter)
                .setUnconscious(unconsciousAfter)
                .setDying(dyingAfter)
                .setUpdatedAt(LocalDateTime.now());
        characterCardService.updateDiceCharacter(card);

        String characterName = stringValue(rule, "characterName");
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("characterName", characterName);
        outcome.put("rawHealing", rawHealing);
        resolution.setOutcome(outcome);
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("hpBefore", hpBefore);
        effect.put("hpAfter", hpAfter);
        effect.put("hpGain", hpGain);
        effect.put("majorWoundBefore", majorWoundBefore);
        effect.put("majorWound", majorWoundAfter);
        effect.put("majorWoundChanged", majorWoundBefore != majorWoundAfter);
        effect.put("unconsciousBefore", unconsciousBefore);
        effect.put("unconscious", unconsciousAfter);
        effect.put("unconsciousChanged", unconsciousBefore != unconsciousAfter);
        effect.put("dyingBefore", dyingBefore);
        effect.put("dying", dyingAfter);
        effect.put("dyingChanged", dyingBefore != dyingAfter);
        resolution.setEffect(effect);
    }

    private void settleMajorWoundConResult(
            DiceRollResult result, DiceResolutionDataVO resolution) {
        Map<String, Object> rule = resolution.getRule();
        CocDiceRules.CheckResolution check = CocDiceRules.resolveCheck(
                requireRoll(result),
                intValue(rule, "targetValue"),
                CocCheckDifficulty.REGULAR);
        CocCharacter card = characterCardService.lockDiceCharacter(
                longValue(rule, "runId"), longValue(rule, "cardId"));
        if (card == null || Boolean.TRUE.equals(card.getDead())) {
            throw new UserRequestException("角色卡无法进行重伤CON检定");
        }
        boolean unconsciousBefore = Boolean.TRUE.equals(card.getUnconscious());
        boolean failed = check.outcome() == CocCheckOutcome.FAILURE
                || check.outcome() == CocCheckOutcome.FUMBLE;
        boolean unconsciousAfter = unconsciousBefore || failed;
        card.setUnconscious(unconsciousAfter).setUpdatedAt(LocalDateTime.now());
        characterCardService.updateDiceCharacter(card);

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("characterName", stringValue(rule, "characterName"));
        outcome.put("category", check.outcome().name());
        resolution.setOutcome(outcome);
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("unconsciousBefore", unconsciousBefore);
        effect.put("unconscious", unconsciousAfter);
        resolution.setEffect(effect);
    }

    private void settleUnconsciousRecoveryResult(
            DiceRollResult result, DiceResolutionDataVO resolution) {
        Map<String, Object> rule = resolution.getRule();
        CocDiceRules.CheckResolution check = CocDiceRules.resolveCheck(
                requireRoll(result),
                intValue(rule, "targetValue"),
                CocCheckDifficulty.REGULAR);
        CocCharacter card = characterCardService.lockDiceCharacter(
                longValue(rule, "runId"), longValue(rule, "cardId"));
        if (card == null || Boolean.TRUE.equals(card.getDying())
                || Boolean.TRUE.equals(card.getDead())) {
            throw new UserRequestException("角色当前不能进行昏迷恢复检定");
        }
        boolean unconsciousBefore = Boolean.TRUE.equals(
                card.getUnconscious());
        boolean success = check.outcome() == CocCheckOutcome.SUCCESS
                || check.outcome() == CocCheckOutcome.CRITICAL_SUCCESS;
        boolean unconsciousAfter = unconsciousBefore && !success;
        card.setUnconscious(unconsciousAfter)
                .setUpdatedAt(LocalDateTime.now());
        characterCardService.updateDiceCharacter(card);

        resolution.setOutcome(new LinkedHashMap<>(Map.of(
                "characterName", stringValue(rule, "characterName"),
                "category", check.outcome().name())));
        resolution.setEffect(new LinkedHashMap<>(Map.of(
                "unconsciousBefore", unconsciousBefore,
                "unconscious", unconsciousAfter)));
    }

    private List<DiceRollResult> appendTemporaryInsanityRoundIfNeeded(
            DiceRollSummary summary, List<DiceRollResult> allResults) {
        int sourceRound = summary.getRoundCount();
        List<DiceRollResult> currentRound = safeResults(allResults).stream()
                .filter(result -> Objects.equals(sourceRound, result.getRoundNo()))
                .toList();
        if (currentRound.isEmpty()
                || currentRound.stream().anyMatch(result -> result.getResolvedAt() == null)
                || currentRound.stream().noneMatch(result ->
                        DiceRollConstant.TYPE_SAN_LOSS.equals(
                                resolution(result).getType()))) {
            return List.of();
        }

        Set<Long> existingSources = safeResults(allResults).stream()
                .filter(result -> DiceRollConstant.TYPE_TEMPORARY_INSANITY_TYPE.equals(
                        resolution(result).getType())
                        || DiceRollConstant.TYPE_TEMPORARY_INSANITY_DURATION.equals(
                        resolution(result).getType()))
                .map(result -> resolution(result).getSourceResultId())
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<DiceRollResult> qualified = currentRound.stream()
                .filter(result -> DiceRollConstant.TYPE_SAN_LOSS.equals(
                        resolution(result).getType()))
                .filter(result -> effectInt(result, "sanLoss") >= 5)
                .filter(result -> result.getId() != null
                        && !existingSources.contains(result.getId()))
                .toList();
        if (qualified.isEmpty()) {
            return List.of();
        }

        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(qualified.size() * 2);
        int displayOrder = 1;
        for (DiceRollResult sanLoss : qualified) {
            DiceResolutionDataVO sanResolution = resolution(sanLoss);
            Map<String, Object> baseRule = new LinkedHashMap<>();
            baseRule.put("runId", longValue(sanResolution.getRule(), "runId"));
            baseRule.put("cardId", longValue(sanResolution.getRule(), "cardId"));
            baseRule.put("characterName", stringValue(
                    sanResolution.getRule(), "characterName"));
            baseRule.put("sanLoss", effectInt(sanLoss, "sanLoss"));
            drafts.add(insanityDraft(
                    sanLoss,
                    DiceRollConstant.TYPE_TEMPORARY_INSANITY_TYPE,
                    displayOrder++,
                    baseRule));
            drafts.add(insanityDraft(
                    sanLoss,
                    DiceRollConstant.TYPE_TEMPORARY_INSANITY_DURATION,
                    displayOrder++,
                    baseRule));
        }

        List<DiceRollResult> created = internalService.appendDiceRollRound(
                summary.getConversationId(), summary.getId(), drafts);
        updateRoundCountFromCreated(summary, created, sourceRound + 1);
        settleAlreadyRolled(created);
        List<DiceRollResult> combined = mergeResults(allResults, created);
        settleCompletedInsanityPairs(combined);
        refreshSummary(summary, combined);
        return created;
    }

    private DiceRollResultCreateDTO insanityDraft(
            DiceRollResult sanLoss,
            String type,
            int displayOrder,
            Map<String, Object> baseRule) {
        DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
        draft.setCharacterId(sanLoss.getCharacterId());
        draft.setDisplayOrder(displayOrder);
        draft.setDisplayType(type);
        draft.setReason(stringValue(baseRule, "characterName")
                + (DiceRollConstant.TYPE_TEMPORARY_INSANITY_TYPE.equals(type)
                ? "临时疯狂类型"
                : "临时疯狂持续时间"));
        draft.setFormula("1D10");
        draft.setResolutionData(DiceResolutionDataVO.pending(
                type, sanLoss.getId(), baseRule));
        return draft;
    }

    private List<DiceRollResult> appendMajorWoundConRoundIfNeeded(
            DiceRollSummary summary, List<DiceRollResult> allResults) {
        int sourceRound = summary.getRoundCount();
        List<DiceRollResult> currentRound = safeResults(allResults).stream()
                .filter(result -> Objects.equals(sourceRound, result.getRoundNo()))
                .toList();
        if (currentRound.isEmpty()
                || currentRound.stream().anyMatch(result -> result.getResolvedAt() == null)
                || currentRound.stream().noneMatch(result ->
                        DiceRollConstant.TYPE_DAMAGE.equals(
                                resolution(result).getType()))) {
            return List.of();
        }

        Set<Long> existingSources = safeResults(allResults).stream()
                .filter(result -> DiceRollConstant.TYPE_MAJOR_WOUND_CON.equals(
                        resolution(result).getType()))
                .map(result -> resolution(result).getSourceResultId())
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<DiceRollResult> qualified = currentRound.stream()
                .filter(result -> DiceRollConstant.TYPE_DAMAGE.equals(
                        resolution(result).getType()))
                .filter(result -> effectBoolean(result, "majorWoundChanged"))
                .filter(result -> !effectBoolean(result, "unconscious"))
                .filter(result -> result.getId() != null
                        && !existingSources.contains(result.getId()))
                .toList();
        if (qualified.isEmpty()) {
            return List.of();
        }

        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(qualified.size());
        for (int index = 0; index < qualified.size(); index++) {
            DiceRollResult damage = qualified.get(index);
            DiceResolutionDataVO damageResolution = resolution(damage);
            Map<String, Object> damageRule = damageResolution.getRule();
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("runId", longValue(damageRule, "runId"));
            rule.put("cardId", longValue(damageRule, "cardId"));
            rule.put("characterName", stringValue(
                    damageRule, "characterName"));
            rule.put("checkName", "CON");
            rule.put("targetValue", intValue(damageRule, "conValue"));
            rule.put("hpLoss", effectInt(damage, "hpLoss"));

            CocDiceCharacterVO wounded = characterCardService
                    .requireDiceCharacter(
                            longValue(damageRule, "runId"),
                            stringValue(damageRule, "characterName"));

            DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
            draft.setCharacterId(automaticRoller(wounded));
            draft.setDisplayOrder(index + 1);
            draft.setDisplayType(DiceRollConstant.TYPE_MAJOR_WOUND_CON);
            draft.setReason(stringValue(rule, "characterName") + "重伤CON检定");
            draft.setFormula(CocPercentileModifier.NORMAL.formula());
            draft.setResolutionData(DiceResolutionDataVO.pending(
                    DiceRollConstant.TYPE_MAJOR_WOUND_CON,
                    damage.getId(),
                    rule));
            drafts.add(draft);
        }

        List<DiceRollResult> created = internalService.appendDiceRollRound(
                summary.getConversationId(), summary.getId(), drafts);
        updateRoundCountFromCreated(summary, created, sourceRound + 1);
        settleAlreadyRolled(created);
        List<DiceRollResult> combined = mergeResults(allResults, created);
        refreshSummary(summary, combined);
        return created;
    }

    private void settleCompletedInsanityPairs(List<DiceRollResult> allResults) {
        Map<Long, List<DiceRollResult>> bySource = new LinkedHashMap<>();
        for (DiceRollResult result : safeResults(allResults)) {
            String type = resolution(result).getType();
            if (!DiceRollConstant.TYPE_TEMPORARY_INSANITY_TYPE.equals(type)
                    && !DiceRollConstant.TYPE_TEMPORARY_INSANITY_DURATION.equals(type)) {
                continue;
            }
            Long sourceResultId = resolution(result).getSourceResultId();
            if (sourceResultId != null) {
                bySource.computeIfAbsent(
                        sourceResultId, ignored -> new ArrayList<>()).add(result);
            }
        }
        for (List<DiceRollResult> pair : bySource.values()) {
            DiceRollResult typeResult = findInsanityResult(
                    pair, DiceRollConstant.TYPE_TEMPORARY_INSANITY_TYPE);
            DiceRollResult durationResult = findInsanityResult(
                    pair, DiceRollConstant.TYPE_TEMPORARY_INSANITY_DURATION);
            if (typeResult == null || durationResult == null
                    || typeResult.getResolvedAt() == null
                    || durationResult.getResolvedAt() == null
                    || resolution(typeResult).getOutcome() == null
                    || resolution(durationResult).getOutcome() == null
                    || resolution(durationResult).getEffect() != null) {
                continue;
            }

            DiceResolutionDataVO typeResolution = resolution(typeResult);
            DiceResolutionDataVO durationResolution = resolution(durationResult);
            int typeRoll = outcomeInt(typeResult, "typeRoll");
            int durationHours = outcomeInt(durationResult, "durationHours");
            Integer detailRoll = null;
            if (typeRoll == 9 || typeRoll == 10) {
                Object savedDetail = typeResolution.getOutcome().get("detailRoll");
                detailRoll = savedDetail instanceof Number number
                        ? number.intValue()
                        : randomSource.d100();
            }
            CocDiceRules.InsanityResolution insanity = CocDiceRules.resolveInsanity(
                    typeRoll, durationHours, detailRoll);

            Map<String, Object> typeOutcome = new LinkedHashMap<>(
                    typeResolution.getOutcome());
            if (detailRoll != null) {
                typeOutcome.put("detailRoll", detailRoll);
            }
            typeOutcome.put("code", insanity.code());
            typeOutcome.put("display", insanity.display());
            typeResolution.setOutcome(typeOutcome);
            typeResult.setUpdatedAt(LocalDateTime.now());
            internalService.saveResult(typeResult);

            Map<String, Object> rule = typeResolution.getRule();
            CocCharacter card = characterCardService.lockDiceCharacter(
                    longValue(rule, "runId"), longValue(rule, "cardId"));
            if (card == null) {
                throw new UserRequestException("角色卡不存在");
            }
            Boolean insanityBefore = card.getTemporaryInsanity();
            String phaseBefore = card.getTemporaryInsanityPhase();
            Integer durationBefore = card.getTemporaryInsanityRemainingHours();
            card.setTemporaryInsanity(true)
                    .setTemporaryInsanityPhase(insanity.code())
                    .setTemporaryInsanityRemainingHours(durationHours)
                    .setUpdatedAt(LocalDateTime.now());
            characterCardService.updateDiceCharacter(card);

            Map<String, Object> effect = new LinkedHashMap<>();
            effect.put("temporaryInsanityBefore", Boolean.TRUE.equals(insanityBefore));
            effect.put("phaseBefore", phaseBefore);
            effect.put("durationHoursBefore", durationBefore);
            effect.put("temporaryInsanity", true);
            effect.put("phase", insanity.code());
            effect.put("durationHours", durationHours);
            durationResolution.setEffect(effect);
            durationResult.setUpdatedAt(LocalDateTime.now());
            internalService.saveResult(durationResult);
        }
    }

    private DiceRollResult findInsanityResult(
            List<DiceRollResult> pair, String type) {
        return pair.stream()
                .filter(result -> type.equals(resolution(result).getType()))
                .findFirst()
                .orElse(null);
    }

    private List<DiceRollResult> appendMeleeDamageRoundIfReady(
            DiceRollSummary summary,
            List<DiceRollResult> allResults) {
        int attackRound = summary.getRoundCount();
        List<DiceRollResult> attacks = safeResults(allResults).stream()
                .filter(result -> Objects.equals(
                        attackRound, result.getRoundNo()))
                .filter(result -> DiceRollConstant.TYPE_MELEE_ATTACK.equals(
                        resolution(result).getType()))
                .sorted(Comparator.comparing(
                        DiceRollResult::getDisplayOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (attacks.isEmpty()
                || attacks.stream().anyMatch(
                result -> result.getResolvedAt() == null)
                || attacks.stream().anyMatch(result -> Boolean.TRUE.equals(
                resolution(result).getOutcome().get("finalized")))) {
            return List.of();
        }
        DiceRollResult attacker = attacks.stream()
                .filter(result -> "ATTACKER".equals(
                        ruleString(result, "role")))
                .findFirst()
                .orElseThrow(() -> new UserRequestException(
                        "近战攻击检定数据不完整"));
        MeleeDefenseMode defenseMode = MeleeDefenseMode.valueOf(
                ruleString(attacker, "defenseMode"));
        DiceRollResult defender = attacks.stream()
                .filter(result -> "DEFENDER".equals(
                        ruleString(result, "role")))
                .findFirst().orElse(null);
        if (defenseMode != MeleeDefenseMode.NONE && defender == null) {
            throw new UserRequestException("近战防守检定数据不完整");
        }
        CocDiceRules.CheckRank attackerRank = CocDiceRules.CheckRank.valueOf(
                outcomeString(attacker, "rank"));
        CocDiceRules.CheckRank defenderRank = defender == null
                ? null : CocDiceRules.CheckRank.valueOf(
                outcomeString(defender, "rank"));
        CocMeleeRules.Winner winner = CocMeleeRules.winner(
                attackerRank, defenderRank, defenseMode);
        DiceRollResult winningCheck = winner == CocMeleeRules.Winner.ATTACKER
                ? attacker
                : winner == CocMeleeRules.Winner.DEFENDER ? defender : null;
        DiceRollResult source = winningCheck == defender
                && defenseMode == MeleeDefenseMode.DODGE
                ? null : winningCheck;
        String winnerName = winningCheck == null
                ? null : ruleString(winningCheck, "characterName");
        for (DiceRollResult attack : attacks) {
            DiceResolutionDataVO attackResolution = resolution(attack);
            Map<String, Object> outcome = new LinkedHashMap<>(
                    attackResolution.getOutcome());
            outcome.put("finalized", true);
            outcome.put("winner", attack == winningCheck);
            if (winnerName != null) {
                outcome.put("winnerCharacterName", winnerName);
            }
            attackResolution.setOutcome(outcome);
            internalService.saveResult(attack);
        }
        refreshSummary(summary, allResults);
        if (source == null) {
            return List.of();
        }

        Map<String, Object> sourceRule = resolution(source).getRule();
        boolean activeAttack = source == attacker;
        CocDiceRules.CheckRank sourceRank = CocDiceRules.CheckRank.valueOf(
                outcomeString(source, "rank"));
        boolean extreme = activeAttack
                && (sourceRank == CocDiceRules.CheckRank.EXTREME
                || sourceRank == CocDiceRules.CheckRank.CRITICAL);
        CocMeleeRules.DamagePlan plan = CocMeleeRules.damagePlan(
                stringValue(sourceRule, "damageFormula"),
                stringValue(sourceRule, "damageBonus"),
                extreme,
                booleanValue(sourceRule, "canImpale"));

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("conversationId", summary.getConversationId());
        rule.put("runId", longValue(sourceRule, "runId"));
        rule.put("cardId", longValue(sourceRule, "opponentCardId"));
        rule.put("characterName",
                stringValue(sourceRule, "opponentCharacterName"));
        rule.put("sourceCharacterName",
                stringValue(sourceRule, "characterName"));
        rule.put("conValue", intValue(sourceRule, "opponentConValue"));
        int armor = intValue(sourceRule, "opponentArmor");
        rule.put("melee", true);
        rule.put("armor", armor);
        rule.put("automaticArmor", true);
        rule.put("weaponName", stringValue(sourceRule, "weaponName"));
        rule.put("maximumDamage", plan.maximumDamage());
        rule.put("impaling", plan.impaling());
        rule.put("rollBundleKey", "melee:" + source.getId());

        String damageReason = stringValue(sourceRule, "weaponName")
                + "命中" + stringValue(
                sourceRule, "opponentCharacterName");
        List<DiceRollResultCreateDTO> drafts = new ArrayList<>(2);
        int displayOrder = 1;
        if (plan.formula() != null) {
            drafts.add(damageDraft(
                    source.getCharacterId(), displayOrder++, damageReason,
                    applyArmor(plan.formula(), armor), source.getId(), rule));
        }
        if (plan.stun()) {
            drafts.add(stunDraft(
                    source.getCharacterId(), displayOrder,
                    damageReason, source.getId(), rule));
        }
        List<DiceRollResult> created = internalService.appendDiceRollRound(
                summary.getConversationId(), summary.getId(), drafts);
        updateRoundCountFromCreated(summary, created, attackRound + 1);
        settleAlreadyRolled(created);
        refreshSummary(summary, mergeResults(allResults, created));
        return created;
    }

    private List<DiceRollResult> appendFirearmDamageRoundIfReady(
            DiceRollSummary summary,
            List<DiceRollResult> allResults) {
        int attackRound = summary.getRoundCount();
        List<DiceRollResult> attacks = safeResults(allResults).stream()
                .filter(result -> Objects.equals(
                        attackRound, result.getRoundNo()))
                .filter(result -> DiceRollConstant.TYPE_FIREARM_ATTACK.equals(
                        resolution(result).getType()))
                .sorted(Comparator.comparing(
                        DiceRollResult::getDisplayOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
        if (attacks.isEmpty()
                || attacks.stream().anyMatch(
                        result -> result.getResolvedAt() == null)
                || attacks.stream().anyMatch(result -> Boolean.TRUE.equals(
                        resolution(result).getOutcome().get("finalized")))) {
            return List.of();
        }

        boolean malfunctioned = false;
        boolean weaponBroke = false;
        Map<String, FirearmTargetDamage> damageByTarget =
                new LinkedHashMap<>();
        for (DiceRollResult attack : attacks) {
            DiceResolutionDataVO attackResolution = resolution(attack);
            Map<String, Object> rule = attackResolution.getRule();
            Map<String, Object> outcome = new LinkedHashMap<>(
                    attackResolution.getOutcome());
            outcome.put("finalized", true);
            if (malfunctioned) {
                outcome.put("valid", false);
                outcome.put("invalidatedByMalfunction", true);
                attackResolution.setOutcome(outcome);
                internalService.saveResult(attack);
                continue;
            }

            boolean fumble = CocCheckOutcome.FUMBLE.name().equals(
                    Objects.toString(outcome.get("category"), null));
            CocFirearmRules.MalfunctionDecision malfunction =
                    CocFirearmRules.malfunction(
                            requireRoll(attack),
                            fumble,
                            intValue(rule, "malfunctionThreshold"),
                            booleanValue(rule, "fumbleBreaksWeapon"));
            if (malfunction.breaksWeapon()) {
                malfunctioned = true;
                weaponBroke = true;
                outcome.put("valid", false);
                outcome.put("malfunction", true);
                attackResolution.setOutcome(outcome);
                internalService.saveResult(attack);
                continue;
            }
            outcome.put("valid", true);
            if (malfunction.unhandledFumble()) {
                outcome.put("unhandledFumble", true);
            }
            attackResolution.setOutcome(outcome);
            internalService.saveResult(attack);

            String category = Objects.toString(
                    outcome.get("category"), "");
            if (!CocCheckOutcome.SUCCESS.name().equals(category)
                    && !CocCheckOutcome.CRITICAL_SUCCESS.name()
                    .equals(category)) {
                continue;
            }
            String rank = Objects.toString(outcome.get("rank"), "");
            boolean extreme = CocDiceRules.CheckRank.EXTREME.name()
                    .equals(rank)
                    || CocDiceRules.CheckRank.CRITICAL.name().equals(rank);
            CocFirearmRules.DamagePlan plan = CocFirearmRules.damagePlan(
                    com.me.galchat.constant.FirearmFiringMode.valueOf(
                            stringValue(rule, "firingMode")),
                    intValue(rule, "bulletsInGroup"),
                    extreme,
                    booleanValue(rule, "canImpale"),
                    intValue(rule, "difficultyIncrease") >= 2,
                    stringValue(rule, "damageFormula"));
            String targetName = stringValue(
                    rule, "targetCharacterName");
            FirearmTargetDamage target = damageByTarget.computeIfAbsent(
                    targetName,
                    ignored -> new FirearmTargetDamage(
                            longValue(rule, "targetCardId"),
                            targetName,
                            intValue(rule, "targetConValue"),
                            intValue(rule, "targetArmor")));
            plan.hitFormulas().stream()
                    .map(formula -> applyArmor(formula, target.armor()))
                    .forEach(target.formulas()::add);
            target.addStun(plan.stun());
            target.sourceResultIds().add(attack.getId());
            target.addHits(plan.hitCount(), plan.impalingHitCount());
        }

        if (weaponBroke) {
            Map<String, Object> firstRule = resolution(
                    attacks.getFirst()).getRule();
            CocCharacterWeapon weapon = characterCardService
                    .requireWeaponForUpdate(
                            longValue(firstRule, "runId"),
                            stringValue(firstRule, "characterName"),
                            stringValue(firstRule, "weaponName"));
            if (!Boolean.TRUE.equals(weapon.getIsBroken())) {
                weapon.setIsBroken(true);
                characterCardService.updateWeapon(weapon);
            }
        }
        refreshSummary(summary, allResults);
        if (damageByTarget.isEmpty()) {
            return List.of();
        }

        Map<String, Object> firstRule = resolution(
                attacks.getFirst()).getRule();
        List<DiceRollResultCreateDTO> drafts = new ArrayList<>();
        int displayOrder = 1;
        for (FirearmTargetDamage target : damageByTarget.values()) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("conversationId", summary.getConversationId());
            rule.put("runId", longValue(firstRule, "runId"));
            rule.put("cardId", target.cardId());
            rule.put("characterName", target.characterName());
            rule.put("sourceCharacterName",
                    stringValue(firstRule, "characterName"));
            rule.put("conValue", target.conValue());
            rule.put("firearm", true);
            rule.put("armor", target.armor());
            rule.put("automaticArmor", true);
            rule.put("weaponName", stringValue(firstRule, "weaponName"));
            rule.put("sourceResultIds", List.copyOf(
                    target.sourceResultIds()));
            rule.put("hitCount", target.hitCount());
            rule.put("impalingHitCount", target.impalingHitCount());
            rule.put("rollBundleKey", "firearm:"
                    + target.sourceResultIds().getFirst()
                    + ":" + target.cardId());

            String damageReason = stringValue(firstRule, "weaponName")
                    + "命中" + target.characterName();
            Long rollerId = attacks.getFirst().getCharacterId();
            Long sourceResultId = target.sourceResultIds().getFirst();
            if (!target.formulas().isEmpty()) {
                drafts.add(damageDraft(
                        rollerId, displayOrder++, damageReason,
                        String.join("+", target.formulas()),
                        sourceResultId, rule));
            }
            if (target.stun()) {
                drafts.add(stunDraft(
                        rollerId, displayOrder++, damageReason,
                        sourceResultId, rule));
            }
        }
        List<DiceRollResult> created = internalService.appendDiceRollRound(
                summary.getConversationId(), summary.getId(), drafts);
        updateRoundCountFromCreated(summary, created, attackRound + 1);
        settleAlreadyRolled(created);
        refreshSummary(summary, mergeResults(allResults, created));
        return created;
    }

    private void updateRoundCountFromCreated(
            DiceRollSummary summary,
            List<DiceRollResult> created,
            int fallbackRound) {
        int createdRound = safeResults(created).stream()
                .map(DiceRollResult::getRoundNo)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(fallbackRound);
        summary.setRoundCount(Math.max(summary.getRoundCount(), createdRound));
    }

    private int parseMalfunction(String value) {
        if (!StringUtils.hasText(value)) {
            throw new UserRequestException("枪械没有有效的故障值");
        }
        try {
            int threshold = Integer.parseInt(value.trim());
            if (threshold < 1 || threshold > 100) {
                throw new NumberFormatException();
            }
            return threshold;
        } catch (NumberFormatException exception) {
            throw new UserRequestException("枪械故障值必须是1到100的整数");
        }
    }

    private Long automaticRoller(CocDiceCharacterVO card) {
        return switch (Objects.toString(card.actorType(), "")) {
            case "PLAYER" -> null;
            case "BOT" -> Objects.requireNonNullElse(
                    card.participantId(), card.cardId());
            case "NPC" -> card.cardId();
            default -> throw new UserRequestException(
                    "不支持的人物卡控制类型");
        };
    }

    private void refreshSummary(DiceRollSummary summary, List<DiceRollResult> results) {
        int currentRound = summary.getRoundCount();
        boolean pendingUserDice = safeResults(results).stream()
                .filter(result -> Objects.equals(currentRound, result.getRoundNo()))
                .anyMatch(this::isPendingUserDice);
        summary.setStatus(pendingUserDice
                        ? DiceRollConstant.STATUS_PENDING
                        : DiceRollConstant.STATUS_COMPLETED)
                .setTotalResult(summaryFormatter.rebuildTotalResult(results))
                .setUpdatedAt(LocalDateTime.now());
        internalService.saveSummary(summary);
    }

    private boolean isPendingUserDice(DiceRollResult result) {
        DiceRollResultVO data = result.getResultData();
        return result.getCharacterId() == null
                && result.getResolvedAt() == null
                && data != null
                && data.getResult() == null
                && data.getModules() != null
                && !data.getModules().isEmpty();
    }

    private KpDiceToolResult toolResult(
            DiceRollSummary summary, List<DiceRollResult> created) {
        List<DiceRollResult> safeCreated = safeResults(created);
        String semantic = StringUtils.hasText(summary.getTotalResult())
                ? summary.getTotalResult()
                : availableSemantic(safeCreated);
        return new KpDiceToolResult(
                DiceRollSummaryVO.from(summary),
                safeCreated.stream().map(DiceRollDetailVO::from).toList(),
                semantic);
    }

    private String availableSemantic(List<DiceRollResult> results) {
        if (results.isEmpty()) {
            return null;
        }
        if (results.stream().anyMatch(result -> DiceRollConstant.TYPE_OPPOSED_CHECK.equals(
                resolution(result).getType()))
                && results.stream().anyMatch(result -> result.getResolvedAt() == null)) {
            return null;
        }
        List<DiceRollResult> resolved = results.stream()
                .filter(result -> result.getResolvedAt() != null)
                .toList();
        if (resolved.isEmpty()) {
            return null;
        }
        String text = summaryFormatter.formatRound(resolved);
        return text.isBlank() ? null : text;
    }

    private DiceRollResultCreateDTO checkDraft(
            CocDiceCharacterVO card,
            String checkName,
            int targetValue,
            CocCheckDifficulty difficulty,
            CocPercentileModifier modifier,
            List<Map<String, Object>> modifierFactors,
            boolean pushed,
            String type,
            String reason,
            int displayOrder,
            String tieWinnerCharacterName) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("cardId", card.cardId());
        rule.put("characterName", card.name());
        rule.put("checkName", checkName);
        rule.put("targetValue", targetValue);
        rule.put("difficulty", difficulty.name());
        rule.put("modifier", modifier.name());
        putModifierFactors(rule, modifierFactors);
        rule.put("pushed", pushed);
        if (DiceRollConstant.TYPE_OPPOSED_CHECK.equals(type)) {
            rule.put("tieWinnerCharacterName", tieWinnerCharacterName);
        }
        DiceRollResultCreateDTO draft = new DiceRollResultCreateDTO();
        draft.setCharacterId(card.participantId());
        draft.setDisplayOrder(displayOrder);
        draft.setDisplayType(type);
        draft.setReason(reason.trim());
        draft.setFormula(modifier.formula());
        draft.setResolutionData(DiceResolutionDataVO.pending(type, null, rule));
        return draft;
    }

    private List<Map<String, Object>> kpModifierFactors(
            CocPercentileModifier requestedModifier,
            String reason) {
        CocPercentileModifier modifier = normalizeModifier(requestedModifier);
        List<Map<String, Object>> factors = new ArrayList<>();
        if (modifier == CocPercentileModifier.NORMAL) {
            return factors;
        }
        if (!StringUtils.hasText(reason)) {
            throw new UserRequestException("使用奖励骰或惩罚骰时必须填写奖惩骰原因");
        }
        boolean bonus = modifier == CocPercentileModifier.BONUS_1
                || modifier == CocPercentileModifier.BONUS_2;
        int diceCount = modifier == CocPercentileModifier.BONUS_2
                || modifier == CocPercentileModifier.PENALTY_2 ? 2 : 1;
        factors.add(modifierFactor(
                "KP", bonus ? "BONUS" : "PENALTY", diceCount,
                "KP_MODIFIER", reason.trim()));
        return factors;
    }

    private Map<String, Object> modifierFactor(
            String source,
            String kind,
            int diceCount,
            String code,
            String reason) {
        Map<String, Object> factor = new LinkedHashMap<>();
        factor.put("source", source);
        factor.put("kind", kind);
        factor.put("diceCount", diceCount);
        factor.put("code", code);
        factor.put("reason", reason);
        return factor;
    }

    private void putModifierFactors(
            Map<String, Object> rule,
            List<Map<String, Object>> modifierFactors) {
        if (modifierFactors != null && !modifierFactors.isEmpty()) {
            rule.put("modifierFactors", List.copyOf(modifierFactors));
        }
    }

    private List<KpDiceRequestDTOs.CheckTarget> requireTargets(
            List<KpDiceRequestDTOs.CheckTarget> targets, boolean opposed) {
        if (targets == null || targets.isEmpty()
                || (opposed && targets.size() < 2)) {
            throw new UserRequestException(opposed
                    ? "对抗检定至少需要两个角色"
                    : "检定角色不能为空");
        }
        Set<String> names = new HashSet<>();
        for (KpDiceRequestDTOs.CheckTarget target : targets) {
            if (target == null || !StringUtils.hasText(target.characterName())
                    || target.checkNames() == null
                    || target.checkNames().isEmpty()
                    || target.checkNames().stream().anyMatch(
                            checkName -> !StringUtils.hasText(checkName))) {
                throw new UserRequestException("检定角色名和检定项不能为空");
            }
            if (!names.add(target.characterName().trim())) {
                throw new UserRequestException("同一次检定不能重复选择角色");
            }
        }
        return List.copyOf(targets);
    }

    private List<KpDiceRequestDTOs.DamageTarget> requireDamageTargets(
            List<KpDiceRequestDTOs.DamageTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new UserRequestException("伤害目标不能为空");
        }
        Set<String> targetNames = new HashSet<>();
        for (KpDiceRequestDTOs.DamageTarget target : targets) {
            if (target == null
                    || !StringUtils.hasText(target.targetCharacterName())
                    || !StringUtils.hasText(target.formula())) {
                throw new UserRequestException("伤害目标和公式不能为空");
            }
            if (!targetNames.add(target.targetCharacterName().trim())) {
                throw new UserRequestException("同一轮不能重复选择伤害目标");
            }
        }
        return List.copyOf(targets);
    }

    private List<KpDiceRequestDTOs.HealingTarget> requireHealingTargets(
            List<KpDiceRequestDTOs.HealingTarget> targets,
            HealingSourceMode sourceMode) {
        if (targets == null || targets.isEmpty()) {
            throw new UserRequestException("回血目标不能为空");
        }
        Set<String> targetNames = new HashSet<>();
        for (KpDiceRequestDTOs.HealingTarget target : targets) {
            if (target == null
                    || !StringUtils.hasText(target.targetCharacterName())
                    || !StringUtils.hasText(target.formula())) {
                throw new UserRequestException("回血目标和公式不能为空");
            }
            if (!targetNames.add(target.targetCharacterName().trim())) {
                throw new UserRequestException("同一轮不能重复选择回血目标");
            }
            boolean hasSource = StringUtils.hasText(target.sourceCharacterName());
            if (sourceMode == HealingSourceMode.STANDALONE && hasSource) {
                throw new UserRequestException("无来源回血不能指定前置来源角色");
            }
            if (sourceMode == HealingSourceMode.FOLLOW_UP && !hasSource) {
                throw new UserRequestException("后续回血必须指定前置来源角色");
            }
        }
        return List.copyOf(targets);
    }

    private DiceRollResult requireSuccessfulHealingSource(
            List<DiceRollResult> existing, String sourceName) {
        DiceRollResult source = safeResults(existing).stream()
                .filter(result -> DiceRollConstant.TYPE_CHECK.equals(
                        resolution(result).getType()))
                .filter(result -> sourceName.equals(
                        ruleString(result, "characterName")))
                .max(Comparator.comparing(
                        DiceRollResult::getRoundNo,
                        Comparator.nullsFirst(Integer::compareTo)))
                .orElseThrow(() -> new UserRequestException(
                        "找不到来源角色“" + sourceName + "”的前置单次检定"));
        String category = outcomeString(source, "category");
        if (!CocCheckOutcome.CRITICAL_SUCCESS.name().equals(category)
                && !CocCheckOutcome.SUCCESS.name().equals(category)) {
            throw new UserRequestException("前置检定未成功，不能结算回血");
        }
        return source;
    }

    private List<String> requireCharacterNames(List<String> characterNames) {
        if (characterNames == null || characterNames.isEmpty()) {
            throw new UserRequestException("角色名不能为空");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String name : characterNames) {
            if (!StringUtils.hasText(name) || !unique.add(name.trim())) {
                throw new UserRequestException("角色名不能为空或重复");
            }
        }
        return List.copyOf(unique);
    }

    private int requireCheckValue(CocDiceCharacterVO card, String checkName) {
        if (card == null || card.cardId() == null) {
            throw new UserRequestException("角色卡不存在");
        }
        Integer value = card.checkValues().get(checkName.trim());
        if (value == null || value < 1 || value > 100) {
            throw new UserRequestException(
                    "角色“" + card.name() + "”没有可用的“" + checkName.trim() + "”检定值");
        }
        return value;
    }

    private CheckSelection selectHighestCheck(
            CocDiceCharacterVO card, List<String> checkNames) {
        CheckSelection highest = null;
        for (String checkName : checkNames) {
            String normalizedName = checkName.trim();
            int value = requireCheckValue(card, normalizedName);
            if (highest == null || value > highest.value()) {
                highest = new CheckSelection(normalizedName, value);
            }
        }
        return highest;
    }

    private record CheckSelection(String name, int value) {
    }

    private CocPercentileModifier normalizeModifier(CocPercentileModifier modifier) {
        return modifier == null ? CocPercentileModifier.NORMAL : modifier;
    }

    private void requireContext(Long conversationId, Long runId) {
        if (conversationId == null || runId == null) {
            throw new UserRequestException("群聊或跑团上下文不存在");
        }
        conversationService.requireActive(conversationId);
    }

    private void requireRequest(Object request, String reason) {
        if (request == null || !StringUtils.hasText(reason)) {
            throw new UserRequestException("掷骰请求或原因不能为空");
        }
    }

    private void requireFormula(String formula, String message) {
        if (!StringUtils.hasText(formula)) {
            throw new UserRequestException(message);
        }
        try {
            DiceUtils.prepare(formula.trim());
        } catch (IllegalArgumentException exception) {
            throw new UserRequestException("骰子公式无效：" + exception.getMessage());
        }
    }

    private CocDamageRules.DamageExpression requireDamageExpression(
            String formula) {
        try {
            return CocDamageRules.parse(formula);
        } catch (IllegalArgumentException exception) {
            throw new UserRequestException(
                    "伤害公式无效：" + exception.getMessage());
        }
    }

    private void requireConversation(DiceRollSummary summary, Long conversationId) {
        if (!conversationId.equals(summary.getConversationId())) {
            throw new UserRequestException("前一次掷骰不属于当前群聊");
        }
    }

    private List<DiceRollResult> mergeResults(
            List<DiceRollResult> persisted, List<DiceRollResult> additional) {
        Map<Object, DiceRollResult> merged = new LinkedHashMap<>();
        for (DiceRollResult result : safeResults(persisted)) {
            merged.put(resultKey(result), result);
        }
        for (DiceRollResult result : safeResults(additional)) {
            merged.put(resultKey(result), result);
        }
        return merged.values().stream()
                .sorted(Comparator
                        .comparing(DiceRollResult::getRoundNo,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(DiceRollResult::getDisplayOrder,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(DiceRollResult::getId,
                                Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private Object resultKey(DiceRollResult result) {
        return result.getId() == null ? result : result.getId();
    }

    private List<DiceRollResult> safeResults(List<DiceRollResult> results) {
        return results == null ? List.of() : results;
    }

    private DiceResolutionDataVO resolution(DiceRollResult result) {
        if (result == null || result.getResolutionData() == null
                || result.getResolutionData().getRule() == null) {
            throw new UserRequestException("掷骰结算数据不存在");
        }
        return result.getResolutionData();
    }

    private int requireRoll(DiceRollResult result) {
        if (result.getResultData() == null || result.getResultData().getResult() == null) {
            throw new UserRequestException("掷骰结果尚未完成");
        }
        return result.getResultData().getResult();
    }

    private int intValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new UserRequestException("掷骰规则字段无效：" + key);
    }

    private Long longValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new UserRequestException("掷骰规则字段无效：" + key);
    }

    private int effectInt(DiceRollResult result, String key) {
        Map<String, Object> effect = resolution(result).getEffect();
        if (effect != null && effect.get(key) instanceof Number number) {
            return number.intValue();
        }
        throw new UserRequestException("掷骰影响字段无效：" + key);
    }

    private boolean effectBoolean(DiceRollResult result, String key) {
        Map<String, Object> effect = resolution(result).getEffect();
        if (effect != null && effect.get(key) instanceof Boolean value) {
            return value;
        }
        throw new UserRequestException("掷骰影响字段无效：" + key);
    }

    private boolean booleanValue(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new UserRequestException("掷骰规则字段无效：" + key);
    }

    private int outcomeInt(DiceRollResult result, String key) {
        Map<String, Object> outcome = resolution(result).getOutcome();
        if (outcome != null && outcome.get(key) instanceof Number number) {
            return number.intValue();
        }
        throw new UserRequestException("掷骰结果字段无效：" + key);
    }

    private String ruleString(DiceRollResult result, String key) {
        return stringValue(resolution(result).getRule(), key);
    }

    private String outcomeString(DiceRollResult result, String key) {
        Map<String, Object> outcome = resolution(result).getOutcome();
        return outcome == null ? null : Objects.toString(outcome.get(key), null);
    }

    private String stringValue(Map<String, Object> values, String key) {
        String value = values == null ? null : Objects.toString(values.get(key), null);
        if (!StringUtils.hasText(value)) {
            throw new UserRequestException("掷骰规则字段无效：" + key);
        }
        return value;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int normalizeArmor(Integer armor) {
        return Math.max(0, Objects.requireNonNullElse(armor, 0));
    }

    private String applyArmor(String formula, int armor) {
        if (armor <= 0) {
            return formula;
        }
        String grouped = formula.startsWith("(") && formula.endsWith(")")
                ? formula : "(" + formula + ")";
        String adjusted = "max(0," + grouped + "-" + armor + ")";
        DiceUtils.prepare(adjusted);
        return adjusted;
    }

    private static final class FirearmTargetDamage {
        private final Long cardId;
        private final String characterName;
        private final int conValue;
        private final int armor;
        private final List<String> formulas = new ArrayList<>();
        private final List<Long> sourceResultIds = new ArrayList<>();
        private int hitCount;
        private int impalingHitCount;
        private boolean stun;

        private FirearmTargetDamage(
                Long cardId,
                String characterName,
                int conValue,
                int armor) {
            this.cardId = cardId;
            this.characterName = characterName;
            this.conValue = conValue;
            this.armor = armor;
        }

        private Long cardId() {
            return cardId;
        }

        private String characterName() {
            return characterName;
        }

        private int conValue() {
            return conValue;
        }

        private int armor() {
            return armor;
        }

        private List<String> formulas() {
            return formulas;
        }

        private List<Long> sourceResultIds() {
            return sourceResultIds;
        }

        private int hitCount() {
            return hitCount;
        }

        private int impalingHitCount() {
            return impalingHitCount;
        }

        private boolean stun() {
            return stun;
        }

        private void addHits(int hits, int impalingHits) {
            hitCount += hits;
            impalingHitCount += impalingHits;
        }

        private void addStun(boolean added) {
            stun = stun || added;
        }
    }

    private record EffectiveMeleeWeapon(
            String name,
            String skillName,
            String damage,
            boolean canImpale) {
    }
}
