package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.CocBackgroundPromptConstant;
import com.me.galchat.constant.CocWeaponCatalogConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.domain.dto.StepwiseCharacterCardModels;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterCreationDraft;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserInfo;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.exception.CharacterCardCreationException;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.mapper.CocCharacterCreationDraftMapper;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.UserInfoMapper;
import com.me.galchat.mapper.UserWorldPrefixMapper;
import com.me.galchat.utils.CurrentHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StepwiseCharacterCardCreationService {

    static final String MODE = "STEP_STANDARD";
    private static final int RULES_VERSION = 1;
    private static final int MAX_STARTING_WEAPONS = 3;
    private static final List<String> ATTRIBUTE_ORDER =
            List.of("STR", "CON", "SIZ", "DEX", "APP", "INT", "POW", "EDU");
    private static final List<String> BACKGROUND_CATEGORIES = List.of(
            "APPEARANCE", "IDEOLOGY", "SIGNIFICANT_PEOPLE",
            "MEANINGFUL_LOCATIONS", "TREASURED_POSSESSIONS", "TRAITS");
    private static final Set<String> ROLLABLE_BACKGROUND = Set.of(
            "IDEOLOGY", "SIGNIFICANT_PEOPLE", "MEANINGFUL_LOCATIONS",
            "TREASURED_POSSESSIONS", "TRAITS");

    private final CocCharacterCreationDraftMapper draftMapper;
    private final GroupConversationMapper conversationMapper;
    private final UserWorldPrefixMapper worldMapper;
    private final UserInfoMapper userInfoMapper;
    private final CharacterTemplateMapper templateMapper;
    private final CocModuleMapper moduleMapper;
    private final CocSkillDefMapper skillDefMapper;
    private final CharacterSkillResolver skillResolver;
    private final CharacterCardGenerationRandom random;

    public CharacterCardGenerationModels.DraftView create(
            StepwiseCharacterCardModels.CreateRequest request) {
        if (request == null) {
            throw new UserRequestException("请求不能为空");
        }
        StepContext context = requireContext(request.runId(), request.participantId());
        StepwiseCharacterCardModels.Identity identity = new StepwiseCharacterCardModels.Identity(
                request.runId(), request.participantId(), context.actorType(),
                requireText(request.name(), "姓名"), context.playerName(), context.image(),
                requireText(request.occupation(), "职业"), requireAge(request.age()),
                requireText(request.sex(), "性别"), requireText(request.residence(), "住地"),
                requireText(request.birthplace(), "出身"));
        List<CocCharacterCreationDraft> active = draftMapper.selectList(
                activeQuery(request.runId(), request.participantId()));
        if (active != null && !active.isEmpty()) {
            return view(active.getFirst());
        }
        StepwiseCharacterCardModels.State stepState = new StepwiseCharacterCardModels.State(
                identity, null,
                new StepwiseCharacterCardModels.Occupation(identity.occupation(), false),
                null, null, null);
        LocalDateTime now = LocalDateTime.now();
        CocCharacterCreationDraft draft = new CocCharacterCreationDraft()
                .setOwnerUserId((long) currentUserId())
                .setRunId(request.runId()).setParticipantId(request.participantId())
                .setCreationMode(MODE).setStatus("IN_PROGRESS")
                .setCurrentStep("ATTRIBUTES").setNextAction("ROLL_ATTRIBUTES")
                .setOperationStatus("IDLE").setVersion(1)
                .setRulesVersion(RULES_VERSION)
                .setState(stepDraftState(stepState, null))
                .setCreatedAt(now).setUpdatedAt(now);
        draftMapper.insert(draft);
        return view(draft);
    }

    public CharacterCardGenerationModels.DraftView updateIdentity(
            Long draftId, StepwiseCharacterCardModels.IdentityUpdateRequest request) {
        CocCharacterCreationDraft draft = requireMutable(draftId,
                request == null ? null : request.expectedVersion());
        StepwiseCharacterCardModels.State state = requireState(draft);
        StepwiseCharacterCardModels.Identity old = state.identity();
        boolean attributesRolled = state.attributes() != null;
        if (attributesRolled && request.age() != null
                && !Objects.equals(old.age(), request.age())) {
            throw new UserRequestException("属性投掷后不能修改年龄");
        }
        boolean occupationConfirmed = state.occupation() != null
                && Boolean.TRUE.equals(state.occupation().confirmed());
        if (occupationConfirmed && request.occupation() != null
                && !Objects.equals(old.occupation(), request.occupation().trim())) {
            throw new UserRequestException("职业确认后不能修改职业");
        }
        StepwiseCharacterCardModels.Identity updated = new StepwiseCharacterCardModels.Identity(
                old.runId(), old.participantId(), old.actorType(),
                optionalText(request.name(), old.name(), "姓名"), old.playerName(), old.image(),
                optionalText(request.occupation(), old.occupation(), "职业"),
                request.age() == null ? old.age() : requireAge(request.age()),
                optionalText(request.sex(), old.sex(), "性别"),
                optionalText(request.residence(), old.residence(), "住地"),
                optionalText(request.birthplace(), old.birthplace(), "出身"));
        StepwiseCharacterCardModels.Occupation occupation = occupationConfirmed
                ? state.occupation()
                : new StepwiseCharacterCardModels.Occupation(updated.occupation(), false);
        StepwiseCharacterCardModels.State updatedState =
                new StepwiseCharacterCardModels.State(
                        updated, state.attributes(), occupation, state.skills(),
                        state.background(), state.equipment());
        CharacterCardVO preview = state.equipment() != null
                && Boolean.TRUE.equals(state.equipment().confirmed())
                ? buildPreview(updatedState) : draft.getState().preview();
        updateState(draft, updatedState, preview);
        finish(draft, null, "UPDATE_IDENTITY");
        return view(draft);
    }

    public CharacterCardGenerationModels.DraftView rollAttributes(
            Long draftId, CharacterCardGenerationModels.ActionRequest request) {
        CocCharacterCreationDraft draft = requireOwnedStepDraft(draftId);
        requireRequest(request);
        rejectReusedRequestId(draft, request.requestId(), "ROLL_ATTRIBUTES");
        if (replay(draft, request.requestId(), "ROLL_ATTRIBUTES")) {
            return view(draft);
        }
        requireVersion(draft, request.expectedVersion());
        requireAction(draft, "ATTRIBUTES", "ROLL_ATTRIBUTES");
        StepwiseCharacterCardModels.State state = requireState(draft);
        List<StepwiseCharacterCardModels.DiceRoll> rolls = new ArrayList<>();
        Map<String, Integer> raw = new LinkedHashMap<>();
        for (String code : ATTRIBUTE_ORDER) {
            StepwiseCharacterCardModels.DiceRoll roll = rollAttribute(code);
            rolls.add(roll);
            raw.put(code, roll.result());
        }
        List<StepwiseCharacterCardModels.DiceRoll> luckRolls = new ArrayList<>();
        luckRolls.add(roll3d6x5("LUCK"));
        if (state.identity().age() < 20) {
            luckRolls.add(roll3d6x5("LUCK"));
        }
        int luck = luckRolls.stream().mapToInt(StepwiseCharacterCardModels.DiceRoll::result)
                .max().orElseThrow();
        Map<String, Integer> finalValues = new LinkedHashMap<>(raw);
        applyFixedAgeAdjustments(finalValues, state.identity().age());
        List<StepwiseCharacterCardModels.EducationGrowth> educationGrowths =
                rollEducationGrowth(finalValues, state.identity().age());
        int physicalPenalty = physicalPenalty(state.identity().age());
        String nextAction;
        String currentStep;
        StepwiseCharacterCardModels.DerivedValues derived = null;
        if (physicalPenalty == 0) {
            derived = derived(finalValues, state.identity().age());
            currentStep = "OCCUPATION";
            nextAction = "CONFIRM_OCCUPATION";
        } else if (availablePhysicalPoints(finalValues, state.identity().age())
                < physicalPenalty) {
            currentStep = "ATTRIBUTES";
            nextAction = "RESTART_REQUIRED";
        } else {
            currentStep = "ATTRIBUTES";
            nextAction = "SUBMIT_AGE_ADJUSTMENT";
        }
        StepwiseCharacterCardModels.Attributes attributes =
                new StepwiseCharacterCardModels.Attributes(
                        Map.copyOf(raw), List.copyOf(rolls), List.copyOf(luckRolls), luck,
                        List.copyOf(educationGrowths), Map.of(),
                        Map.copyOf(finalValues), derived);
        StepwiseCharacterCardModels.State updated = new StepwiseCharacterCardModels.State(
                state.identity(), attributes, state.occupation(), state.skills(),
                state.background(), state.equipment());
        draft.setCurrentStep(currentStep).setNextAction(nextAction);
        updateState(draft, updated, null);
        finish(draft, request.requestId(), "ROLL_ATTRIBUTES");
        return view(draft);
    }

    public CharacterCardGenerationModels.DraftView applyAgeAdjustment(
            Long draftId, StepwiseCharacterCardModels.AgeAdjustmentRequest request) {
        CocCharacterCreationDraft draft = requireMutable(draftId,
                request == null ? null : request.expectedVersion());
        requireAction(draft, "ATTRIBUTES", "SUBMIT_AGE_ADJUSTMENT");
        StepwiseCharacterCardModels.State state = requireState(draft);
        StepwiseCharacterCardModels.Attributes attributes = state.attributes();
        if (attributes == null) {
            throw new UserRequestException("请先完成属性投掷");
        }
        int age = state.identity().age();
        Map<String, Integer> penalties = agePenaltyMap(request, age);
        int required = physicalPenalty(age);
        int total = penalties.values().stream().mapToInt(Integer::intValue).sum();
        if (total != required) {
            throw new UserRequestException("年龄物理减值合计必须为" + required);
        }
        Map<String, Integer> finalValues = new LinkedHashMap<>(attributes.finalValues());
        for (Map.Entry<String, Integer> entry : penalties.entrySet()) {
            int value = finalValues.get(entry.getKey());
            if (entry.getValue() > value) {
                throw new UserRequestException(entry.getKey() + "不能被扣成负数");
            }
            finalValues.put(entry.getKey(), value - entry.getValue());
        }
        StepwiseCharacterCardModels.Attributes adjusted =
                new StepwiseCharacterCardModels.Attributes(
                        attributes.raw(), attributes.rolls(), attributes.luckRolls(),
                        attributes.luck(), attributes.educationGrowths(),
                        Map.copyOf(penalties), Map.copyOf(finalValues),
                        derived(finalValues, age));
        StepwiseCharacterCardModels.State updated = new StepwiseCharacterCardModels.State(
                state.identity(), adjusted, state.occupation(), state.skills(),
                state.background(), state.equipment());
        draft.setCurrentStep("OCCUPATION").setNextAction("CONFIRM_OCCUPATION");
        updateState(draft, updated, null);
        finish(draft, null, "APPLY_AGE_ADJUSTMENT");
        return view(draft);
    }

    public CharacterCardGenerationModels.DraftView saveOccupation(
            Long draftId, StepwiseCharacterCardModels.OccupationRequest request) {
        CocCharacterCreationDraft draft = requireMutable(draftId,
                request == null ? null : request.expectedVersion());
        requireAction(draft, "OCCUPATION", "CONFIRM_OCCUPATION");
        StepwiseCharacterCardModels.State state = requireState(draft);
        String occupationText = requireText(request.occupation(), "职业");
        boolean confirmed = Boolean.TRUE.equals(request.confirmed());
        StepwiseCharacterCardModels.Identity old = state.identity();
        StepwiseCharacterCardModels.Identity identity =
                new StepwiseCharacterCardModels.Identity(
                        old.runId(), old.participantId(), old.actorType(), old.name(),
                        old.playerName(), old.image(), occupationText, old.age(), old.sex(),
                        old.residence(), old.birthplace());
        StepwiseCharacterCardModels.State updated = new StepwiseCharacterCardModels.State(
                identity, state.attributes(),
                new StepwiseCharacterCardModels.Occupation(occupationText, confirmed),
                state.skills(), state.background(), state.equipment());
        if (confirmed) {
            draft.setCurrentStep("SKILLS").setNextAction("CONFIRM_SKILLS");
        }
        updateState(draft, updated, null);
        finish(draft, null, "SAVE_OCCUPATION");
        return view(draft);
    }

    public CharacterCardGenerationModels.DraftView saveSkills(
            Long draftId, StepwiseCharacterCardModels.SkillsRequest request) {
        CocCharacterCreationDraft draft = requireMutable(draftId,
                request == null ? null : request.expectedVersion());
        requireAction(draft, "SKILLS", "CONFIRM_SKILLS");
        StepwiseCharacterCardModels.State state = requireState(draft);
        Map<String, Integer> values = requireFinalAttributes(state);
        CocCharacter baseCharacter = baseCharacter(state, null);
        List<CocSkillDef> definitions = safe(skillDefMapper.selectList(null));
        Map<Long, CocSkillDef> byId = new LinkedHashMap<>();
        definitions.forEach(definition -> byId.put(definition.getId(), definition));
        List<StepwiseCharacterCardModels.SkillItem> items = new ArrayList<>();
        Set<String> displayNames = new LinkedHashSet<>();
        int spent = 0;
        for (StepwiseCharacterCardModels.SkillAllocation allocation
                : safe(request.allocations())) {
            if (allocation == null || allocation.skillDefId() == null) {
                throw new UserRequestException("技能定义id不能为空");
            }
            CocSkillDef definition = byId.get(allocation.skillDefId());
            if (definition == null) {
                throw new UserRequestException("未知技能定义：" + allocation.skillDefId());
            }
            int points = allocation.allocatedPoints() == null
                    ? 0 : allocation.allocatedPoints();
            if (points < 0) {
                throw new UserRequestException("技能分配点数不能为负数");
            }
            String specialization = normalize(allocation.specialization());
            String displayName = definition.getName();
            if (Boolean.TRUE.equals(definition.getAllowSpecialization())) {
                if (specialization == null) {
                    throw new UserRequestException(definition.getName() + "必须填写专攻");
                }
                displayName = definition.getName() + ":" + specialization;
            } else if (specialization != null) {
                throw new UserRequestException(definition.getName() + "不接受专攻");
            }
            if (!displayNames.add(displayName)) {
                throw new UserRequestException("技能不能重复分配：" + displayName);
            }
            int base = skillResolver.resolveBaseValue(definition, baseCharacter);
            if (definition.getBaseValue() == null
                    && definition.getBaseFormula() == null) {
                throw new UserRequestException("技能父项不能直接分配：" + definition.getName());
            }
            if ("克苏鲁神话".equals(definition.getName()) && points > 0) {
                throw new UserRequestException("新建人物卡不能为克苏鲁神话分配点数");
            }
            int finalValue = base + points;
            if (finalValue > 99) {
                throw new UserRequestException(displayName + "最终值不能超过99");
            }
            spent += points;
            items.add(new StepwiseCharacterCardModels.SkillItem(
                    definition.getId(), displayName, definition.getCategory(),
                    Objects.requireNonNullElse(specialization, ""), base, points,
                    finalValue, finalValue / 2, finalValue / 5));
        }
        int maxAttribute = values.values().stream().mapToInt(Integer::intValue)
                .max().orElseThrow();
        int budget = values.get("EDU") * 2 + values.get("INT") * 2
                + maxAttribute * 2;
        if (spent > budget) {
            throw new UserRequestException("技能分配点数超过上限");
        }
        boolean confirmed = Boolean.TRUE.equals(request.confirmed());
        StepwiseCharacterCardModels.Skills skills =
                new StepwiseCharacterCardModels.Skills(
                        budget, spent, budget - spent, List.copyOf(items), confirmed);
        StepwiseCharacterCardModels.State updated = new StepwiseCharacterCardModels.State(
                state.identity(), state.attributes(), state.occupation(), skills,
                state.background(), state.equipment());
        if (confirmed) {
            draft.setCurrentStep("BACKGROUND").setNextAction("CONFIRM_BACKGROUND");
        }
        updateState(draft, updated, null);
        finish(draft, null, "SAVE_SKILLS");
        return view(draft);
    }

    public CharacterCardGenerationModels.DraftView rollBackground(
            Long draftId, String category,
            StepwiseCharacterCardModels.BackgroundRollRequest request) {
        CocCharacterCreationDraft draft = requireOwnedStepDraft(draftId);
        if (request == null) {
            throw new UserRequestException("请求不能为空");
        }
        requireRequest(new CharacterCardGenerationModels.ActionRequest(
                request.requestId(), request.expectedVersion()));
        String normalizedCategory = normalizeCode(category);
        String action = "ROLL_BACKGROUND_" + normalizedCategory;
        rejectReusedRequestId(draft, request.requestId(), action);
        if (replay(draft, request.requestId(), action)) {
            return view(draft);
        }
        requireVersion(draft, request.expectedVersion());
        requireAction(draft, "BACKGROUND", "CONFIRM_BACKGROUND");
        if (!ROLLABLE_BACKGROUND.contains(normalizedCategory)) {
            throw new UserRequestException("该背景类别不支持随机提示：" + category);
        }
        StepwiseCharacterCardModels.State state = requireState(draft);
        StepwiseCharacterCardModels.Background old = state.background();
        Map<String, StepwiseCharacterCardModels.BackgroundPrompt> prompts =
                new LinkedHashMap<>(old == null || old.prompts() == null
                        ? Map.of() : old.prompts());
        prompts.put(normalizedCategory, backgroundPrompt(normalizedCategory));
        StepwiseCharacterCardModels.Background background =
                new StepwiseCharacterCardModels.Background(
                        old == null || old.entries() == null ? Map.of() : old.entries(),
                        Map.copyOf(prompts),
                        old == null ? null : old.keyConnectionCategory(),
                        old == null ? null : old.keyConnectionText(), false);
        StepwiseCharacterCardModels.State updated = new StepwiseCharacterCardModels.State(
                state.identity(), state.attributes(), state.occupation(), state.skills(),
                background, state.equipment());
        updateState(draft, updated, null);
        finish(draft, request.requestId(), action);
        return view(draft);
    }

    public CharacterCardGenerationModels.DraftView saveBackground(
            Long draftId, StepwiseCharacterCardModels.BackgroundRequest request) {
        CocCharacterCreationDraft draft = requireMutable(draftId,
                request == null ? null : request.expectedVersion());
        requireAction(draft, "BACKGROUND", "CONFIRM_BACKGROUND");
        StepwiseCharacterCardModels.State state = requireState(draft);
        Map<String, String> entries = normalizeBackgroundEntries(request.entries());
        boolean confirmed = Boolean.TRUE.equals(request.confirmed());
        String keyCategory = normalizeCode(request.keyConnectionCategory());
        String keyText = null;
        if (keyCategory != null) {
            if ("APPEARANCE".equals(keyCategory) || !entries.containsKey(keyCategory)) {
                throw new UserRequestException("关键连接必须指向一项非形象背景");
            }
            keyText = entries.get(keyCategory);
        }
        if (confirmed) {
            if (entries.size() < 3 || entries.size() > 6) {
                throw new UserRequestException("确认背景时必须填写3到6项背景");
            }
            if (keyCategory == null) {
                throw new UserRequestException("确认背景前必须选择关键连接");
            }
        }
        StepwiseCharacterCardModels.Background old = state.background();
        StepwiseCharacterCardModels.Background background =
                new StepwiseCharacterCardModels.Background(
                        Map.copyOf(entries),
                        old == null || old.prompts() == null ? Map.of() : old.prompts(),
                        keyCategory, keyText, confirmed);
        StepwiseCharacterCardModels.State updated = new StepwiseCharacterCardModels.State(
                state.identity(), state.attributes(), state.occupation(), state.skills(),
                background, state.equipment());
        if (confirmed) {
            draft.setCurrentStep("EQUIPMENT").setNextAction("CONFIRM_EQUIPMENT");
        }
        updateState(draft, updated, null);
        finish(draft, null, "SAVE_BACKGROUND");
        return view(draft);
    }

    public CharacterCardGenerationModels.DraftView saveEquipment(
            Long draftId, StepwiseCharacterCardModels.EquipmentRequest request) {
        CocCharacterCreationDraft draft = requireMutable(draftId,
                request == null ? null : request.expectedVersion());
        requireAction(draft, "EQUIPMENT", "CONFIRM_EQUIPMENT");
        StepwiseCharacterCardModels.State state = requireState(draft);
        StepContext context = requireContext(draft.getRunId(), draft.getParticipantId());
        String moduleEra = context.module() == null ? null : normalize(context.module().getEra());
        String era = moduleEra == null ? requireText(request.era(), "时代") : moduleEra;
        List<CocCharacterWeapon> weapons = buildWeapons(request.weapons(), era);
        boolean confirmed = Boolean.TRUE.equals(request.confirmed());
        StepwiseCharacterCardModels.Equipment equipment =
                new StepwiseCharacterCardModels.Equipment(
                        era, normalize(request.equipmentText()), normalize(request.assetsText()),
                        normalize(request.spendingLevel()), normalize(request.cash()),
                        weapons, confirmed);
        StepwiseCharacterCardModels.State updated = new StepwiseCharacterCardModels.State(
                state.identity(), state.attributes(), state.occupation(), state.skills(),
                state.background(), equipment);
        CharacterCardVO preview = confirmed ? buildPreview(updated) : null;
        if (confirmed) {
            draft.setStatus("PREVIEW_READY").setNextAction("COMPLETE");
        }
        updateState(draft, updated, preview);
        finish(draft, null, "SAVE_EQUIPMENT");
        return view(draft);
    }

    public StepwiseCharacterCardModels.RulesView getRules() {
        List<StepwiseCharacterCardModels.AttributeRule> attributes = List.of(
                new StepwiseCharacterCardModels.AttributeRule("STR", "3D6*5"),
                new StepwiseCharacterCardModels.AttributeRule("CON", "3D6*5"),
                new StepwiseCharacterCardModels.AttributeRule("SIZ", "(2D6+6)*5"),
                new StepwiseCharacterCardModels.AttributeRule("DEX", "3D6*5"),
                new StepwiseCharacterCardModels.AttributeRule("APP", "3D6*5"),
                new StepwiseCharacterCardModels.AttributeRule("INT", "(2D6+6)*5"),
                new StepwiseCharacterCardModels.AttributeRule("POW", "3D6*5"),
                new StepwiseCharacterCardModels.AttributeRule("EDU", "(2D6+6)*5"));
        List<StepwiseCharacterCardModels.SkillRule> skills =
                safe(skillDefMapper.selectList(null)).stream()
                        .map(definition -> new StepwiseCharacterCardModels.SkillRule(
                                definition.getId(), definition.getName(), definition.getCategory(),
                                definition.getBaseValue(), definition.getBaseFormula(),
                                definition.getAllowSpecialization(), definition.getParentName()))
                        .toList();
        List<StepwiseCharacterCardModels.BackgroundRule> backgrounds =
                BACKGROUND_CATEGORIES.stream()
                        .map(code -> new StepwiseCharacterCardModels.BackgroundRule(
                                code, ROLLABLE_BACKGROUND.contains(code)))
                        .toList();
        List<StepwiseCharacterCardModels.WeaponRule> weapons =
                CocWeaponCatalogConstant.weapons().values().stream()
                        .filter(CocWeaponCatalogConstant.WeaponDefinition::autoSelectable)
                        .sorted(java.util.Comparator.comparing(
                                CocWeaponCatalogConstant.WeaponDefinition::code))
                        .map(this::weaponRule)
                        .toList();
        return new StepwiseCharacterCardModels.RulesView(
                RULES_VERSION, attributes, skills, backgrounds, weapons,
                List.of("1920S", "MODERN"));
    }

    public CharacterCardGenerationModels.DraftView abandon(
            Long draftId, Integer expectedVersion) {
        CocCharacterCreationDraft draft = requireMutable(draftId, expectedVersion);
        draft.setStatus("ABANDONED").setNextAction(null);
        finish(draft, null, "ABANDON");
        return view(draft);
    }

    private StepwiseCharacterCardModels.BackgroundPrompt backgroundPrompt(
            String category) {
        int first = random.roll(10);
        int second = "SIGNIFICANT_PEOPLE".equals(category) ? random.roll(10) : 1;
        CharacterCardGenerationModels.BackgroundRolls rolls =
                CocBackgroundPromptConstant.rolls(
                        "IDEOLOGY".equals(category) ? first : 1,
                        "SIGNIFICANT_PEOPLE".equals(category) ? first : 1,
                        second,
                        "MEANINGFUL_LOCATIONS".equals(category) ? first : 1,
                        "TREASURED_POSSESSIONS".equals(category) ? first : 1,
                        "TRAITS".equals(category) ? first : 1);
        if ("SIGNIFICANT_PEOPLE".equals(category)) {
            return new StepwiseCharacterCardModels.BackgroundPrompt(
                    category, List.of(first, second),
                    List.of(promptCode("SIGNIFICANT_PERSON_WHO", first),
                            promptCode("SIGNIFICANT_PERSON_REASON", second)),
                    List.of(rolls.directions().get("significantPersonWho"),
                            rolls.directions().get("significantPersonReason")));
        }
        String directionKey = switch (category) {
            case "IDEOLOGY" -> "ideology";
            case "MEANINGFUL_LOCATIONS" -> "meaningfulLocation";
            case "TREASURED_POSSESSIONS" -> "treasuredPossession";
            case "TRAITS" -> "trait";
            default -> throw new UserRequestException("未知背景类别：" + category);
        };
        return new StepwiseCharacterCardModels.BackgroundPrompt(
                category, List.of(first), List.of(promptCode(category, first)),
                List.of(rolls.directions().get(directionKey)));
    }

    private String promptCode(String category, int roll) {
        return category + "_" + String.format("%02d", roll);
    }

    private Map<String, String> normalizeBackgroundEntries(
            Map<String, String> requested) {
        Map<String, String> result = new LinkedHashMap<>();
        if (requested == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String category = normalizeCode(entry.getKey());
            if (!BACKGROUND_CATEGORIES.contains(category)) {
                throw new UserRequestException("未知背景类别：" + entry.getKey());
            }
            String text = normalize(entry.getValue());
            if (text == null) {
                continue;
            }
            if (text.length() > 1000) {
                throw new UserRequestException(category + "背景不能超过1000个字符");
            }
            result.put(category, text);
        }
        return result;
    }

    private List<CocCharacterWeapon> buildWeapons(
            List<StepwiseCharacterCardModels.WeaponInput> inputs,
            String era) {
        List<StepwiseCharacterCardModels.WeaponInput> requested = safe(inputs);
        if (requested.size() > MAX_STARTING_WEAPONS) {
            throw new UserRequestException("至多选择3件武器");
        }
        Map<String, CocWeaponCatalogConstant.WeaponDefinition> available =
                new LinkedHashMap<>();
        CocWeaponCatalogConstant.autoSelectableForEra(era)
                .forEach(definition -> available.put(definition.code(), definition));
        Set<String> selected = new LinkedHashSet<>();
        List<CocCharacterWeapon> result = new ArrayList<>();
        for (StepwiseCharacterCardModels.WeaponInput input : requested) {
            if (input == null) {
                throw new UserRequestException("武器不能为空");
            }
            String code = normalizeCode(input.code());
            CocWeaponCatalogConstant.WeaponDefinition definition = available.get(code);
            if (definition == null) {
                throw new UserRequestException("武器不在当前时代的可选武器表中");
            }
            if (!selected.add(definition.code())) {
                throw new UserRequestException("不能重复选择武器：" + definition.name());
            }
            result.add(canonicalWeapon(definition));
        }
        return List.copyOf(result);
    }

    private StepwiseCharacterCardModels.WeaponRule weaponRule(
            CocWeaponCatalogConstant.WeaponDefinition definition) {
        List<String> eras = switch (definition.era()) {
            case TWENTIES -> List.of("1920S");
            case MODERN -> List.of("MODERN");
            case BOTH -> List.of("1920S", "MODERN");
        };
        return new StepwiseCharacterCardModels.WeaponRule(
                definition.code(), definition.name(), definition.requiredSkillName(),
                definition.damage(), definition.range(), definition.attacksPerRound(),
                definition.ammoCapacity(), definition.malfunction(), eras,
                definition.kind().name(), definition.canImpale(),
                definition.abnormal(), definition.riskTags(), definition.notes());
    }

    private CocCharacterWeapon canonicalWeapon(
            CocWeaponCatalogConstant.WeaponDefinition definition) {
        return new CocCharacterWeapon()
                .setName(definition.name())
                .setSkillName(definition.requiredSkillName())
                .setDamage(definition.damage())
                .setRange(definition.range())
                .setAttacksPerRound(definition.attacksPerRound())
                .setAmmoCapacity(definition.ammoCapacity())
                .setRemainingAmmo(definition.ammoCapacity())
                .setMalfunction(definition.malfunction())
                .setCanImpale(definition.canImpale())
                .setIsBroken(false)
                .setAbnormal(definition.abnormal())
                .setRiskTags(definition.riskTags())
                .setNotes(definition.notes());
    }

    private CharacterCardVO buildPreview(StepwiseCharacterCardModels.State state) {
        CocCharacter character = baseCharacter(state, state.equipment().era());
        List<CocCharacterSkill> skillOverrides = state.skills().items().stream()
                .filter(item -> item.allocatedPoints() > 0)
                .map(item -> new CocCharacterSkill()
                        .setSkillDefId(item.skillDefId())
                        .setDisplayName(item.displayName())
                        .setCategory(item.category())
                        .setSpecialization(item.specialization())
                        .setBaseValue(item.baseValue())
                        .setValue(item.finalValue())
                        .setIsCustom(false))
                .toList();
        Map<String, String> entries = state.background().entries();
        CocCharacterProfile profile = new CocCharacterProfile()
                .setAppearance(entries.get("APPEARANCE"))
                .setIdeology(entries.get("IDEOLOGY"))
                .setSignificantPeople(entries.get("SIGNIFICANT_PEOPLE"))
                .setMeaningfulLocations(entries.get("MEANINGFUL_LOCATIONS"))
                .setTreasuredPossessions(entries.get("TREASURED_POSSESSIONS"))
                .setTraits(entries.get("TRAITS"))
                .setKeyConnectionCategory(state.background().keyConnectionCategory())
                .setKeyConnectionText(state.background().keyConnectionText())
                .setEquipmentText(state.equipment().equipmentText())
                .setAssetsText(state.equipment().assetsText())
                .setSpendingLevel(state.equipment().spendingLevel())
                .setCash(state.equipment().cash());
        return new CharacterCardVO(
                character, skillOverrides, state.equipment().weapons(), profile);
    }

    private CocCharacter baseCharacter(
            StepwiseCharacterCardModels.State state, String era) {
        Map<String, Integer> values = requireFinalAttributes(state);
        StepwiseCharacterCardModels.DerivedValues derived = state.attributes().derived();
        if (derived == null) {
            derived = derived(values, state.identity().age());
        }
        StepwiseCharacterCardModels.Identity identity = state.identity();
        return new CocCharacter()
                .setActorType(identity.actorType())
                .setParticipantId(identity.participantId())
                .setName(identity.name()).setPlayerName(identity.playerName())
                .setImage(identity.image()).setOccupation(identity.occupation())
                .setSex(identity.sex()).setAge(identity.age()).setEra(era)
                .setBirthplace(identity.birthplace()).setResidence(identity.residence())
                .setCreationMethod("STEP")
                .setStr(values.get("STR")).setCon(values.get("CON"))
                .setSiz(values.get("SIZ")).setDex(values.get("DEX"))
                .setApp(values.get("APP")).setIntValue(values.get("INT"))
                .setPow(values.get("POW")).setEdu(values.get("EDU"))
                .setDamageBonus(derived.damageBonus()).setBuild(derived.build())
                .setMov(derived.mov())
                .setHpCurrent(derived.hp()).setHpMax(derived.hp())
                .setSanCurrent(derived.san()).setSanMax(99)
                .setMpCurrent(derived.mp()).setMpMax(derived.mp())
                .setLuckCurrent(state.attributes().luck()).setArmor(0)
                .setMajorWound(false).setUnconscious(false).setDying(false)
                .setDead(false).setTemporaryInsanity(false)
                .setInCover(false).setCoverActionForfeitPending(false)
                .setStunnedRemainingRounds(0).setRestrainedByCharacterId(null)
                .setMeleeAttackedThisRound(false);
    }

    private Map<String, Integer> requireFinalAttributes(
            StepwiseCharacterCardModels.State state) {
        if (state.attributes() == null || state.attributes().finalValues() == null
                || !state.attributes().finalValues().keySet().containsAll(ATTRIBUTE_ORDER)) {
            throw new UserRequestException("最终属性尚未完成");
        }
        return state.attributes().finalValues();
    }

    private LambdaQueryWrapper<CocCharacterCreationDraft> activeQuery(
            Long runId, Long participantId) {
        LambdaQueryWrapper<CocCharacterCreationDraft> query =
                new LambdaQueryWrapper<CocCharacterCreationDraft>()
                        .eq(CocCharacterCreationDraft::getOwnerUserId, currentUserId())
                        .eq(CocCharacterCreationDraft::getRunId, runId)
                        .in(CocCharacterCreationDraft::getStatus,
                                List.of("IN_PROGRESS", "PREVIEW_READY"))
                        .orderByDesc(CocCharacterCreationDraft::getId);
        return participantId == null
                ? query.isNull(CocCharacterCreationDraft::getParticipantId)
                : query.eq(CocCharacterCreationDraft::getParticipantId, participantId);
    }

    private StepwiseCharacterCardModels.DiceRoll rollAttribute(String code) {
        if (Set.of("SIZ", "INT", "EDU").contains(code)) {
            List<Integer> dice = List.of(random.roll(6), random.roll(6));
            return new StepwiseCharacterCardModels.DiceRoll(
                    code, "(2D6+6)*5", dice,
                    (dice.get(0) + dice.get(1) + 6) * 5);
        }
        return roll3d6x5(code);
    }

    private StepwiseCharacterCardModels.DiceRoll roll3d6x5(String code) {
        List<Integer> dice = List.of(random.roll(6), random.roll(6), random.roll(6));
        return new StepwiseCharacterCardModels.DiceRoll(
                code, "3D6*5", dice,
                dice.stream().mapToInt(Integer::intValue).sum() * 5);
    }

    private void applyFixedAgeAdjustments(Map<String, Integer> values, int age) {
        if (age < 20) {
            values.put("EDU", Math.max(0, values.get("EDU") - 5));
        }
        int appPenalty = age < 40 ? 0 : age < 50 ? 5 : age < 60 ? 10
                : age < 70 ? 15 : age < 80 ? 20 : 25;
        values.put("APP", Math.max(0, values.get("APP") - appPenalty));
    }

    private List<StepwiseCharacterCardModels.EducationGrowth> rollEducationGrowth(
            Map<String, Integer> values, int age) {
        int count = age < 20 ? 0 : age < 40 ? 1 : age < 50 ? 2
                : age < 60 ? 3 : 4;
        List<StepwiseCharacterCardModels.EducationGrowth> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int before = values.get("EDU");
            int check = random.roll(100);
            Integer increase = null;
            int after = before;
            if (check > before) {
                increase = random.roll(10);
                after = Math.min(99, before + increase);
                values.put("EDU", after);
            }
            result.add(new StepwiseCharacterCardModels.EducationGrowth(
                    check, increase, before, after));
        }
        return result;
    }

    private Map<String, Integer> agePenaltyMap(
            StepwiseCharacterCardModels.AgeAdjustmentRequest request, int age) {
        if (request == null) {
            throw new UserRequestException("请求不能为空");
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        if (age < 20) {
            requireZero(request.conPenalty(), "CON");
            requireZero(request.dexPenalty(), "DEX");
            result.put("STR", penalty(request.strPenalty(), "STR"));
            result.put("SIZ", penalty(request.sizPenalty(), "SIZ"));
        } else {
            requireZero(request.sizPenalty(), "SIZ");
            result.put("STR", penalty(request.strPenalty(), "STR"));
            result.put("CON", penalty(request.conPenalty(), "CON"));
            result.put("DEX", penalty(request.dexPenalty(), "DEX"));
        }
        return result;
    }

    private int penalty(Integer value, String name) {
        int normalized = value == null ? 0 : value;
        if (normalized < 0 || normalized % 5 != 0) {
            throw new UserRequestException(name + "年龄减值必须为非负且是5的倍数");
        }
        return normalized;
    }

    private void requireZero(Integer value, String name) {
        if (value != null && value != 0) {
            throw new UserRequestException("当前年龄不能扣减" + name);
        }
    }

    private int physicalPenalty(int age) {
        if (age < 20) return 5;
        if (age < 40) return 0;
        if (age < 50) return 5;
        if (age < 60) return 10;
        if (age < 70) return 20;
        if (age < 80) return 40;
        return 80;
    }

    private int availablePhysicalPoints(Map<String, Integer> values, int age) {
        List<String> names = age < 20
                ? List.of("STR", "SIZ") : List.of("STR", "CON", "DEX");
        return names.stream().mapToInt(values::get).sum();
    }

    private StepwiseCharacterCardModels.DerivedValues derived(
            Map<String, Integer> values, int age) {
        CharacterCardRules.DerivedValues derived = CharacterCardRules.derive(
                values.get("STR"), values.get("CON"), values.get("SIZ"),
                values.get("DEX"), values.get("POW"), age);
        return new StepwiseCharacterCardModels.DerivedValues(
                derived.damageBonus(), derived.build(), derived.mov(),
                derived.hp(), derived.san(), derived.mp());
    }

    private CocCharacterCreationDraft requireMutable(Long draftId, Integer version) {
        CocCharacterCreationDraft draft = requireOwnedStepDraft(draftId);
        requireVersion(draft, version);
        if (!List.of("IN_PROGRESS", "PREVIEW_READY").contains(draft.getStatus())) {
            throw failure("COMPLETED".equals(draft.getStatus())
                    ? "DRAFT_ALREADY_COMPLETED" : "VALIDATION_FAILED",
                    "人物卡草稿当前不可修改", draft);
        }
        return draft;
    }

    private CocCharacterCreationDraft requireOwnedStepDraft(Long draftId) {
        if (draftId == null) {
            throw new UserRequestException("草稿id不能为空");
        }
        CocCharacterCreationDraft draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new CharacterCardCreationException(
                    "DRAFT_NOT_FOUND", "人物卡草稿不存在", null, null, null);
        }
        if (!Objects.equals(draft.getOwnerUserId(), (long) currentUserId())) {
            throw new UserAuthException("无权访问该人物卡草稿");
        }
        if (!MODE.equals(draft.getCreationMode())) {
            throw failure("DRAFT_MODE_MISMATCH",
                    "该接口只适用于步进人物卡草稿", draft);
        }
        return draft;
    }

    private void requireRequest(CharacterCardGenerationModels.ActionRequest request) {
        if (request == null || request.requestId() == null
                || request.requestId().isBlank() || request.requestId().length() > 100) {
            throw new UserRequestException("requestId不能为空且不能超过100个字符");
        }
    }

    private void requireVersion(CocCharacterCreationDraft draft, Integer version) {
        if (version == null || !Objects.equals(draft.getVersion(), version)) {
            throw failure("DRAFT_VERSION_CONFLICT",
                    "人物卡草稿版本已变化，请刷新后重试", draft);
        }
    }

    private void requireAction(
            CocCharacterCreationDraft draft, String step, String action) {
        if (!step.equals(draft.getCurrentStep())
                || !action.equals(draft.getNextAction())) {
            throw failure("STEP_ORDER_CONFLICT",
                    "人物卡创建步骤不匹配，当前应执行：" + draft.getNextAction(),
                    draft);
        }
    }

    private boolean replay(
            CocCharacterCreationDraft draft, String requestId, String action) {
        return Objects.equals(draft.getLastRequestId(), requestId)
                && action.equals(draft.getLastAction());
    }

    private void rejectReusedRequestId(
            CocCharacterCreationDraft draft, String requestId, String action) {
        if (Objects.equals(draft.getLastRequestId(), requestId)
                && !action.equals(draft.getLastAction())) {
            throw failure("IDEMPOTENCY_KEY_REUSED",
                    "requestId已被其他操作使用", draft);
        }
    }

    private void finish(
            CocCharacterCreationDraft draft, String requestId, String action) {
        int expectedVersion = draft.getVersion();
        draft.setLastRequestId(requestId).setLastAction(action)
                .setOperationStatus("IDLE")
                .setVersion(expectedVersion + 1)
                .setUpdatedAt(LocalDateTime.now());
        if (draftMapper.updateWithExpectedVersion(draft, expectedVersion) == 0) {
            throw failure("DRAFT_VERSION_CONFLICT",
                    "人物卡草稿版本已变化，请刷新后重试", draft);
        }
    }

    private void updateState(
            CocCharacterCreationDraft draft,
            StepwiseCharacterCardModels.State stepwise,
            CharacterCardVO preview) {
        draft.setState(stepDraftState(stepwise, preview));
    }

    private CharacterCardGenerationModels.DraftState stepDraftState(
            StepwiseCharacterCardModels.State stepwise,
            CharacterCardVO preview) {
        return new CharacterCardGenerationModels.DraftState(
                1, null, null, null, null, preview, stepwise);
    }

    private StepwiseCharacterCardModels.State requireState(
            CocCharacterCreationDraft draft) {
        if (draft.getState() == null || draft.getState().stepwise() == null) {
            throw new UserRequestException("步进人物卡草稿数据不完整");
        }
        return draft.getState().stepwise();
    }

    private CharacterCardGenerationModels.DraftView view(
            CocCharacterCreationDraft draft) {
        return new CharacterCardGenerationModels.DraftView(
                draft.getId(), draft.getCreationMode(), draft.getStatus(),
                draft.getCurrentStep(), draft.getNextAction(),
                draft.getVersion(), draft.getRulesVersion(), draft.getState());
    }

    private StepContext requireContext(Long runId, Long participantId) {
        if (runId == null) {
            throw new UserRequestException("runId不能为空");
        }
        GroupConversation conversation = conversationMapper.selectById(runId);
        if (conversation == null
                || !GroupChatConstant.MODE_TRPG.equals(conversation.getMode())) {
            throw new UserRequestException("runId必须是TRPG群聊id");
        }
        UserWorldPrefix world = worldMapper.selectById(conversation.getUserWorldId());
        if (world == null || !Objects.equals(world.getUserId(), (long) currentUserId())) {
            throw new UserAuthException("无权访问该跑团");
        }
        CocModule module = conversation.getModuleId() == null
                ? null : moduleMapper.selectById(conversation.getModuleId());
        if (participantId == null) {
            UserInfo user = userInfoMapper.selectById((long) currentUserId());
            if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
                throw new UserRequestException("当前玩家信息不存在");
            }
            return new StepContext("PLAYER", user.getUsername(), null, module);
        }
        CharacterTemplate template = templateMapper.selectById(participantId);
        if (template == null
                || !Objects.equals(template.getWorldId(), conversation.getWorldId())) {
            throw new UserRequestException("角色模板不属于当前跑团世界");
        }
        return new StepContext("BOT", template.getName(), template.getImage(), module);
    }

    private int requireAge(Integer age) {
        if (age == null || age < 15 || age > 90) {
            throw new UserRequestException("年龄必须在15到90之间");
        }
        return age;
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new UserRequestException(label + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 255) {
            throw new UserRequestException(label + "不能超过255个字符");
        }
        return normalized;
    }

    private String optionalText(String value, String fallback, String label) {
        return value == null ? fallback : requireText(value, label);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeCode(String value) {
        String normalized = normalize(value);
        return normalized == null ? null
                : normalized.toUpperCase(java.util.Locale.ROOT);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private int currentUserId() {
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) {
            throw new UserAuthException("用户未登录");
        }
        return userId;
    }

    private CharacterCardCreationException failure(
            String code, String message, CocCharacterCreationDraft draft) {
        return new CharacterCardCreationException(
                code, message,
                draft == null ? null : draft.getVersion(),
                draft == null ? null : draft.getCurrentStep(),
                draft == null ? null : draft.getNextAction());
    }

    private record StepContext(
            String actorType,
            String playerName,
            String image,
            CocModule module) {
    }
}
