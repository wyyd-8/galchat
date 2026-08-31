package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.CocBackgroundPromptConstant;
import com.me.galchat.constant.CocWeaponCatalogConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterCreationDraft;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.exception.CharacterCardCreationException;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.mapper.CocCharacterCreationDraftMapper;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.UserWorldPrefixMapper;
import com.me.galchat.utils.CurrentHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CharacterCardCreationService {

    private static final String AUTO = "AUTO_QUICK_START";
    private static final int RULES_VERSION = 1;

    private final CocCharacterCreationDraftMapper draftMapper;
    private final GroupConversationMapper conversationMapper;
    private final UserWorldPrefixMapper worldMapper;
    private final CharacterTemplateMapper templateMapper;
    private final CocModuleMapper moduleMapper;
    private final CocSkillDefMapper skillDefMapper;
    private final CocCharacterMapper characterMapper;
    private final CocCharacterSkillMapper skillMapper;
    private final CocCharacterWeaponMapper weaponMapper;
    private final CocCharacterProfileMapper profileMapper;
    private final AutoCharacterCardAssembler assembler;
    private final CharacterSkillResolver skillResolver;
    private final CharacterCardGenerationModel generationModel;
    private final CharacterCardGenerationRandom random;

    public CharacterCardGenerationModels.DraftView createAuto(
            CharacterCardGenerationModels.CreateRequest request) {
        Context context = requireContext(
                request == null ? null : request.runId(),
                request == null ? null : request.participantId());
        requireRequestId(request.requestId());
        List<CocCharacterCreationDraft> active = draftMapper.selectList(
                new LambdaQueryWrapper<CocCharacterCreationDraft>()
                        .eq(CocCharacterCreationDraft::getOwnerUserId, currentUserId())
                        .eq(CocCharacterCreationDraft::getRunId, request.runId())
                        .eq(CocCharacterCreationDraft::getParticipantId, request.participantId())
                        .in(CocCharacterCreationDraft::getStatus,
                                List.of("IN_PROGRESS", "PREVIEW_READY"))
                        .orderByDesc(CocCharacterCreationDraft::getId));
        if (active != null && !active.isEmpty()) {
            return view(active.getFirst());
        }
        CharacterCardGenerationModels.DraftState state = generate(context);
        LocalDateTime now = LocalDateTime.now();
        CocCharacterCreationDraft draft = new CocCharacterCreationDraft()
                .setOwnerUserId((long) currentUserId())
                .setRunId(request.runId()).setParticipantId(request.participantId())
                .setCreationMode(AUTO).setStatus("PREVIEW_READY")
                .setCurrentStep("PREVIEW").setNextAction("CONFIRM")
                .setOperationStatus("IDLE").setVersion(1)
                .setRulesVersion(RULES_VERSION).setState(state)
                .setLastRequestId(request.requestId()).setLastAction("CREATE")
                .setCreatedAt(now).setUpdatedAt(now);
        draftMapper.insert(draft);
        return view(draft);
    }

    public CharacterCardGenerationModels.DraftView get(Long draftId) {
        return view(requireOwnedDraft(draftId));
    }

    public CharacterCardGenerationModels.DraftView getActive(
            Long runId, Long participantId) {
        if (participantId == null) {
            requireRunAccess(runId);
        } else {
            requireContext(runId, participantId);
        }
        LambdaQueryWrapper<CocCharacterCreationDraft> query =
                new LambdaQueryWrapper<CocCharacterCreationDraft>()
                        .eq(CocCharacterCreationDraft::getOwnerUserId, currentUserId())
                        .eq(CocCharacterCreationDraft::getRunId, runId)
                        .in(CocCharacterCreationDraft::getStatus,
                                List.of("IN_PROGRESS", "PREVIEW_READY"))
                        .orderByDesc(CocCharacterCreationDraft::getId);
        if (participantId == null) {
            query.isNull(CocCharacterCreationDraft::getParticipantId);
        } else {
            query.eq(CocCharacterCreationDraft::getParticipantId, participantId);
        }
        List<CocCharacterCreationDraft> active = draftMapper.selectList(query);
        return active == null || active.isEmpty() ? null : view(active.getFirst());
    }

    public CharacterCardGenerationModels.DraftView abandon(
            Long draftId, Integer expectedVersion) {
        CocCharacterCreationDraft draft = requireOwnedDraft(draftId);
        if (expectedVersion == null
                || !Objects.equals(draft.getVersion(), expectedVersion)) {
            throw failure("DRAFT_VERSION_CONFLICT",
                    "人物卡草稿版本已变化，请刷新后重试", draft);
        }
        if (!List.of("IN_PROGRESS", "PREVIEW_READY").contains(draft.getStatus())) {
            throw failure("COMPLETED".equals(draft.getStatus())
                            ? "DRAFT_ALREADY_COMPLETED" : "VALIDATION_FAILED",
                    "人物卡草稿当前不可修改", draft);
        }
        draft.setStatus("ABANDONED").setNextAction(null);
        finishAction(draft, null, "ABANDON");
        return view(draft);
    }

    public CharacterCardGenerationModels.DraftView regenerate(
            Long draftId, CharacterCardGenerationModels.ActionRequest request) {
        CocCharacterCreationDraft draft = requireMutableDraft(draftId, request, "REGENERATE");
        requireAutoMode(draft);
        if (isReplay(draft, request, "REGENERATE")) {
            return view(draft);
        }
        Context context = requireContext(draft.getRunId(), draft.getParticipantId());
        draft.setState(generate(context));
        finishAction(draft, request.requestId(), "REGENERATE");
        return view(draft);
    }

    public CharacterCardGenerationModels.DraftView rewriteBackground(
            Long draftId, CharacterCardGenerationModels.ActionRequest request) {
        CocCharacterCreationDraft draft = requireMutableDraft(
                draftId, request, "REWRITE_BACKGROUND");
        requireAutoMode(draft);
        if (isReplay(draft, request, "REWRITE_BACKGROUND")) {
            return view(draft);
        }
        Context context = requireContext(draft.getRunId(), draft.getParticipantId());
        List<CocSkillDef> definitions = skillDefMapper.selectList(null);
        CharacterCardGenerationModels.DraftState canonicalState =
                normalizeState(draft.getState(), definitions);
        List<CocCharacterSkill> effectiveSkills = skillResolver.resolveEffectiveSkills(
                canonicalState.preview().getCharacter(),
                canonicalState.preview().getSkills(), definitions);
        CharacterCardVO effectiveCard = withSkills(
                canonicalState.preview(), effectiveSkills);
        CharacterCardGenerationModels.BackgroundRolls rolls = backgroundRolls();
        CharacterCardGenerationModels.BackgroundPlan background =
                generationModel.generateBackground(
                        context.template(), context.module(),
                        effectiveCard, rolls, availableWeapons(effectiveCard));
        draft.setState(assembler.applyBackground(
                canonicalState, background, rolls, effectiveSkills));
        finishAction(draft, request.requestId(), "REWRITE_BACKGROUND");
        return view(draft);
    }

    @Transactional(rollbackFor = Exception.class)
    public CharacterCardVO complete(
            Long draftId, CharacterCardGenerationModels.ActionRequest request) {
        CocCharacterCreationDraft draft = requireMutableDraft(
                draftId, request, "COMPLETE", true);
        if (isReplay(draft, request, "COMPLETE")) {
            return normalizeState(
                    draft.getState(), skillDefMapper.selectList(null)).preview();
        }
        List<CocSkillDef> definitions = skillDefMapper.selectList(null);
        CharacterCardGenerationModels.DraftState canonicalState =
                normalizeState(draft.getState(), definitions);
        CharacterCardVO preview = canonicalState == null
                ? null : canonicalState.preview();
        if (preview == null || preview.getCharacter() == null
                || preview.getProfile() == null) {
            throw new UserRequestException("人物卡预览尚未完成");
        }
        if (draft.getParticipantId() == null) {
            requireRunAccess(draft.getRunId());
        } else {
            requireContext(draft.getRunId(), draft.getParticipantId());
        }
        requireUniqueFormalCard(draft);
        CocCharacter character = new CocCharacter();
        BeanUtils.copyProperties(preview.getCharacter(), character);
        character.setId(null).setRunId(draft.getRunId())
                .setParticipantId(draft.getParticipantId())
                .setActorType(character.getActorType() == null
                        ? "BOT" : character.getActorType())
                .setCreationMethod(StepwiseCharacterCardCreationService.MODE
                        .equals(draft.getCreationMode()) ? "STEP" : AUTO)
                .setCreatedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now());
        characterMapper.insert(character);
        List<CocCharacterSkill> completedSkills = new ArrayList<>();
        for (CocCharacterSkill source : safe(preview.getSkills())) {
            CocCharacterSkill skill = new CocCharacterSkill();
            BeanUtils.copyProperties(source, skill);
            skill.setId(null).setCharacterId(character.getId());
            skillMapper.insert(skill);
            completedSkills.add(skill);
        }
        List<CocCharacterWeapon> completedWeapons = new ArrayList<>();
        for (CocCharacterWeapon source : safe(preview.getWeapons())) {
            CocCharacterWeapon weapon = new CocCharacterWeapon();
            BeanUtils.copyProperties(source, weapon);
            weapon.setId(null).setCharacterId(character.getId());
            weaponMapper.insert(weapon);
            completedWeapons.add(weapon);
        }
        CocCharacterProfile profile = new CocCharacterProfile();
        BeanUtils.copyProperties(preview.getProfile(), profile);
        profile.setId(null).setCharacterId(character.getId());
        profileMapper.insert(profile);
        CharacterCardVO completedCard = new CharacterCardVO(
                character, List.copyOf(completedSkills),
                List.copyOf(completedWeapons), profile);
        CharacterCardGenerationModels.DraftState oldState = canonicalState;
        draft.setState(new CharacterCardGenerationModels.DraftState(
                oldState.formatVersion(), oldState.buildPlan(), oldState.buildRolls(),
                oldState.backgroundRolls(), oldState.backgroundPlan(), completedCard,
                oldState.stepwise()));
        draft.setStatus("COMPLETED")
                .setCurrentStep(StepwiseCharacterCardCreationService.MODE
                        .equals(draft.getCreationMode()) ? "EQUIPMENT" : "PREVIEW")
                .setNextAction(null).setResultCharacterId(character.getId());
        finishAction(draft, request.requestId(), "COMPLETE");
        return completedCard;
    }

    private CharacterCardGenerationModels.DraftState generate(Context context) {
        List<CocSkillDef> definitions = skillDefMapper.selectList(null);
        List<String> availableSkills = definitions.stream()
                .filter(definition -> definition.getBaseValue() != null
                        || definition.getBaseFormula() != null)
                .filter(definition -> !"克苏鲁神话".equals(definition.getName()))
                .map(CocSkillDef::getName).toList();
        CharacterCardGenerationModels.BuildPlan plan = generationModel.generateBuild(
                context.template(), context.module(), availableSkills);
        CharacterCardGenerationModels.BuildRolls buildRolls = buildRolls(plan);
        CharacterCardGenerationModels.DraftState built = assembler.build(
                context.template(), context.module(), plan, definitions, buildRolls);
        List<CocCharacterSkill> effectiveSkills = skillResolver.resolveEffectiveSkills(
                built.preview().getCharacter(), built.preview().getSkills(), definitions);
        CharacterCardVO effectiveCard = withSkills(built.preview(), effectiveSkills);
        CharacterCardGenerationModels.BackgroundRolls backgroundRolls = backgroundRolls();
        CharacterCardGenerationModels.BackgroundPlan background =
                generationModel.generateBackground(
                        context.template(), context.module(), effectiveCard,
                        backgroundRolls, availableWeapons(effectiveCard));
        return assembler.applyBackground(
                built, background, backgroundRolls, effectiveSkills);
    }

    private CharacterCardVO withSkills(
            CharacterCardVO card, List<CocCharacterSkill> skills) {
        return new CharacterCardVO(
                card.getCharacter(), skills, card.getWeapons(), card.getProfile());
    }

    private CharacterCardGenerationModels.DraftState normalizeState(
            CharacterCardGenerationModels.DraftState state,
            List<CocSkillDef> definitions) {
        if (state == null || state.preview() == null
                || state.preview().getCharacter() == null) {
            return state;
        }
        CharacterCardVO preview = state.preview();
        CharacterCardVO normalizedPreview = withSkills(
                preview,
                skillResolver.normalizeOverrides(
                        preview.getCharacter(), preview.getSkills(), definitions));
        return new CharacterCardGenerationModels.DraftState(
                state.formatVersion(), state.buildPlan(), state.buildRolls(),
                state.backgroundRolls(), state.backgroundPlan(), normalizedPreview,
                state.stepwise());
    }

    private CharacterCardGenerationModels.BuildRolls buildRolls(
            CharacterCardGenerationModels.BuildPlan plan) {
        List<List<Integer>> luckRolls = new ArrayList<>();
        List<Integer> firstLuckDice = roll3d6();
        luckRolls.add(firstLuckDice);
        int luck = firstLuckDice.stream().mapToInt(Integer::intValue).sum() * 5;
        if (plan.age() != null && plan.age() < 20) {
            List<Integer> secondLuckDice = roll3d6();
            luckRolls.add(secondLuckDice);
            luck = Math.max(luck,
                    secondLuckDice.stream().mapToInt(Integer::intValue).sum() * 5);
        }
        int checks = educationCheckCount(plan.age());
        int edu = initialEducation(plan);
        List<Integer> educationChecks = new ArrayList<>();
        List<Integer> increases = new ArrayList<>();
        List<CharacterCardGenerationModels.EducationGrowthRoll> growths =
                new ArrayList<>();
        for (int index = 0; index < checks; index++) {
            int check = random.roll(100);
            educationChecks.add(check);
            Integer increase = null;
            if (check > edu) {
                increase = random.roll(10);
                increases.add(increase);
                edu = Math.min(99, edu + increase);
            }
            growths.add(new CharacterCardGenerationModels.EducationGrowthRoll(
                    check, increase));
        }
        return new CharacterCardGenerationModels.BuildRolls(
                luck, List.copyOf(luckRolls), List.copyOf(educationChecks),
                List.copyOf(increases), List.copyOf(growths));
    }

    private int initialEducation(CharacterCardGenerationModels.BuildPlan plan) {
        int index = plan.attributeOrder().indexOf("EDU");
        if (index < 0) {
            throw new UserRequestException("属性排序缺少EDU");
        }
        int value = List.of(80, 70, 60, 60, 50, 50, 50, 40).get(index);
        return plan.age() != null && plan.age() < 20 ? Math.max(0, value - 5) : value;
    }

    private int educationCheckCount(Integer age) {
        if (age == null || age < 20) return 0;
        if (age < 40) return 1;
        if (age < 50) return 2;
        if (age < 60) return 3;
        return 4;
    }

    private List<Integer> roll3d6() {
        return List.of(random.roll(6), random.roll(6), random.roll(6));
    }

    private CharacterCardGenerationModels.BackgroundRolls backgroundRolls() {
        return CocBackgroundPromptConstant.rolls(
                random.roll(10), random.roll(10), random.roll(10),
                random.roll(10), random.roll(10), random.roll(10));
    }

    private List<CharacterCardGenerationModels.AvailableWeapon> availableWeapons(
            CharacterCardVO card) {
        return CocWeaponCatalogConstant.autoSelectableForEra(
                        card.getCharacter().getEra())
                .stream().flatMap(definition -> card.getSkills().stream()
                        .filter(skill -> definition.requiredSkillName()
                                .equals(skill.getDisplayName()))
                        .findFirst().stream()
                        .map(skill -> new CharacterCardGenerationModels.AvailableWeapon(
                                definition.code(), definition.name(),
                                definition.requiredSkillName(), skill.getValue(),
                                definition.range() + "，伤害" + definition.damage())))
                .toList();
    }

    private CocCharacterCreationDraft requireMutableDraft(
            Long draftId,
            CharacterCardGenerationModels.ActionRequest request,
            String action) {
        return requireMutableDraft(draftId, request, action, false);
    }

    private CocCharacterCreationDraft requireMutableDraft(
            Long draftId,
            CharacterCardGenerationModels.ActionRequest request,
            String action,
            boolean forUpdate) {
        CocCharacterCreationDraft draft = forUpdate
                ? requireOwnedDraftForUpdate(draftId) : requireOwnedDraft(draftId);
        if (request == null) {
            throw new UserRequestException("请求不能为空");
        }
        requireRequestId(request.requestId());
        if (Objects.equals(draft.getLastRequestId(), request.requestId())
                && action.equals(draft.getLastAction())) {
            return draft;
        }
        if (Objects.equals(draft.getLastRequestId(), request.requestId())) {
            throw failure("IDEMPOTENCY_KEY_REUSED",
                    "requestId已被其他操作使用", draft);
        }
        if (request.expectedVersion() == null
                || !Objects.equals(draft.getVersion(), request.expectedVersion())) {
            throw failure("DRAFT_VERSION_CONFLICT",
                    "人物卡草稿版本已变化，请刷新后重试", draft);
        }
        if (!"PREVIEW_READY".equals(draft.getStatus())) {
            throw failure("COMPLETED".equals(draft.getStatus())
                    ? "DRAFT_ALREADY_COMPLETED" : "STEP_ORDER_CONFLICT",
                    "人物卡草稿当前不可修改", draft);
        }
        return draft;
    }

    private void requireAutoMode(CocCharacterCreationDraft draft) {
        if (!AUTO.equals(draft.getCreationMode())) {
            throw failure("DRAFT_MODE_MISMATCH",
                    "该接口只适用于自动人物卡草稿", draft);
        }
    }

    private boolean isReplay(
            CocCharacterCreationDraft draft,
            CharacterCardGenerationModels.ActionRequest request,
            String action) {
        return request != null
                && Objects.equals(draft.getLastRequestId(), request.requestId())
                && action.equals(draft.getLastAction());
    }

    private void finishAction(
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

    private CocCharacterCreationDraft requireOwnedDraft(Long id) {
        if (id == null) {
            throw new UserRequestException("草稿id不能为空");
        }
        CocCharacterCreationDraft draft = draftMapper.selectById(id);
        if (draft == null) {
            throw new CharacterCardCreationException(
                    "DRAFT_NOT_FOUND", "人物卡草稿不存在", null, null, null);
        }
        if (!Objects.equals(draft.getOwnerUserId(), (long) currentUserId())) {
            throw new UserAuthException("无权访问该人物卡草稿");
        }
        return draft;
    }

    private CocCharacterCreationDraft requireOwnedDraftForUpdate(Long id) {
        if (id == null) {
            throw new UserRequestException("草稿id不能为空");
        }
        CocCharacterCreationDraft draft = draftMapper.selectByIdForUpdate(id);
        if (draft == null) {
            throw new CharacterCardCreationException(
                    "DRAFT_NOT_FOUND", "人物卡草稿不存在", null, null, null);
        }
        if (!Objects.equals(draft.getOwnerUserId(), (long) currentUserId())) {
            throw new UserAuthException("无权访问该人物卡草稿");
        }
        return draft;
    }

    private Context requireContext(Long runId, Long participantId) {
        if (runId == null || participantId == null) {
            throw new UserRequestException("runId和participantId不能为空");
        }
        GroupConversation conversation = requireRunAccess(runId);
        CharacterTemplate template = templateMapper.selectById(participantId);
        if (template == null || !Objects.equals(template.getWorldId(), conversation.getWorldId())) {
            throw new UserRequestException("角色模板不属于当前跑团世界");
        }
        if (template.getPersonality() == null || template.getPersonality().isBlank()) {
            throw new UserRequestException("角色性格不能为空");
        }
        CocModule module = conversation.getModuleId() == null
                ? null : moduleMapper.selectById(conversation.getModuleId());
        return new Context(conversation, template, module);
    }

    private GroupConversation requireRunAccess(Long runId) {
        if (runId == null) {
            throw new UserRequestException("runId不能为空");
        }
        GroupConversation conversation = conversationMapper.selectById(runId);
        if (conversation == null
                || !GroupChatConstant.MODE_TRPG.equals(conversation.getMode())) {
            throw new UserRequestException("runId必须是TRPG群聊id");
        }
        UserWorldPrefix world = worldMapper.selectById(conversation.getUserWorldId());
        if (world == null
                || !Objects.equals(world.getUserId(), (long) currentUserId())) {
            throw new UserAuthException("无权访问该跑团");
        }
        return conversation;
    }

    private void requireUniqueFormalCard(CocCharacterCreationDraft draft) {
        List<CocCharacter> matches = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, draft.getRunId())
                        .eq(CocCharacter::getParticipantId, draft.getParticipantId()));
        if (matches != null && !matches.isEmpty()) {
            throw failure("FORMAL_CARD_ALREADY_EXISTS",
                    "该调查员已绑定人物卡", draft);
        }
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 100) {
            throw new UserRequestException("requestId不能为空且不能超过100个字符");
        }
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

    private CharacterCardGenerationModels.DraftView view(
            CocCharacterCreationDraft draft) {
        CharacterCardGenerationModels.DraftState state = normalizeState(
                draft.getState(), skillDefMapper.selectList(null));
        return new CharacterCardGenerationModels.DraftView(
                draft.getId(), draft.getCreationMode(), draft.getStatus(),
                draft.getCurrentStep(), draft.getNextAction(),
                draft.getVersion(), draft.getRulesVersion(), state);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record Context(
            GroupConversation conversation,
            CharacterTemplate template,
            CocModule module) {
    }
}
