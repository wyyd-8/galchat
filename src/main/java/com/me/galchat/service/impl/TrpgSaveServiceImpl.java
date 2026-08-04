package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgSaveCreateDTO;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.TrpgSave;
import com.me.galchat.domain.vo.TrpgSaveOverviewVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.TrpgSaveMapper;
import com.me.galchat.service.ITrpgSaveService;
import com.me.galchat.service.ITrpgSaveSnapshotService;
import com.me.galchat.service.IUserWorldPrefixService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TrpgSaveServiceImpl implements ITrpgSaveService {

    public static final int FORMAT_VERSION = 1;

    private final IUserWorldPrefixService userWorldPrefixService;
    private final GroupConversationMapper conversationMapper;
    private final TrpgSaveMapper saveMapper;
    private final ITrpgSaveSnapshotService snapshotService;
    private final GroupTurnRecoveryService recoveryService;
    private final GroupConversationLockService lockService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public TrpgSaveOverviewVO getSave(Long userId, Long conversationId) {
        GroupConversation conversation = requireTrpgConversation(
                userId, conversationId, false);
        TrpgSave save = saveMapper.selectByConversationId(conversationId);
        return save == null ? null : toOverview(save, conversation);
    }

    @Override
    public TrpgSaveOverviewVO save(
            Long userId,
            Long conversationId,
            TrpgSaveCreateDTO createDTO) {
        GroupConversation conversation = requireTrpgConversation(
                userId, conversationId, true);
        GroupConversationLockService.OwnedLock lock = requireLock(conversationId);
        try {
            recoveryService.assertConversationHasNoNonTerminalTurns(
                    conversationId);
            TrpgSave saved = transactionTemplate.execute(status ->
                    doSave(userId, conversation, createDTO));
            if (saved == null) {
                throw new IllegalStateException("跑团存档事务未返回结果");
            }
            return toOverview(saved, conversation);
        } finally {
            lockService.unlock(lock);
        }
    }

    @Override
    public void load(Long userId, Long conversationId) {
        GroupConversation conversation = requireTrpgConversation(
                userId, conversationId, true);
        TrpgSave save = requireSave(userId, conversationId);
        TrpgSaveSnapshotDTO snapshot = save.getSnapshot();
        validateSnapshot(conversation, snapshot);
        GroupConversationLockService.OwnedLock lock = requireLock(conversationId);
        try {
            recoveryService.assertConversationHasNoNonTerminalTurns(
                    conversationId);
            transactionTemplate.executeWithoutResult(status ->
                    snapshotService.restoreDatabase(conversation, snapshot));
            snapshotService.restoreDerivedState(conversation, snapshot);
        } finally {
            lockService.unlock(lock);
        }
    }

    private TrpgSave doSave(
            Long userId,
            GroupConversation conversation,
            TrpgSaveCreateDTO createDTO) {
        TrpgSaveSnapshotDTO snapshot = snapshotService.capture(conversation);
        if (snapshot == null
                || !Objects.equals(snapshot.getFormatVersion(), FORMAT_VERSION)) {
            throw new IllegalStateException("跑团存档快照格式不正确");
        }
        TrpgSave existing = saveMapper.selectByConversationId(
                conversation.getId());
        TrpgSave save = new TrpgSave()
                .setUserId(userId)
                .setConversationId(conversation.getId())
                .setRemark(normalizeRemark(createDTO))
                .setSavedAt(LocalDateTime.now())
                .setFormatVersion(FORMAT_VERSION)
                .setSnapshot(snapshot);
        if (existing == null) {
            saveMapper.insert(save);
        } else {
            save.setId(existing.getId());
            saveMapper.updateById(save);
        }
        return save;
    }

    private GroupConversation requireTrpgConversation(
            Long userId, Long conversationId, boolean writable) {
        if (conversationId == null) {
            throw new UserRequestException("跑团群聊id不能为空");
        }
        GroupConversation conversation = conversationMapper.selectById(
                conversationId);
        if (conversation == null) {
            throw new UserRequestException("跑团群聊不存在");
        }
        userWorldPrefixService.checkUserWorldAuth(
                userId, conversation.getUserWorldId(), writable);
        if (!GroupChatConstant.MODE_TRPG.equals(conversation.getMode())) {
            throw new UserRequestException("只有TRPG群聊可以使用跑团存档");
        }
        return conversation;
    }

    private GroupConversationLockService.OwnedLock requireLock(
            Long conversationId) {
        GroupConversationLockService.OwnedLock lock = lockService.tryLock(
                conversationId);
        if (lock == null) {
            throw new UserRequestException("跑团正在生成回复，请稍后再存档或读档");
        }
        return lock;
    }

    private TrpgSave requireSave(Long userId, Long conversationId) {
        TrpgSave save = saveMapper.selectByConversationId(conversationId);
        if (save == null || !Objects.equals(save.getUserId(), userId)) {
            throw new UserRequestException("当前跑团没有存档");
        }
        return save;
    }

    private void validateSnapshot(
            GroupConversation conversation, TrpgSaveSnapshotDTO snapshot) {
        if (snapshot == null
                || !Objects.equals(snapshot.getFormatVersion(), FORMAT_VERSION)) {
            throw new UserRequestException("不支持的跑团存档格式");
        }
        if (!Objects.equals(snapshot.getConversationId(), conversation.getId())) {
            throw new UserRequestException("存档不属于当前跑团");
        }
        if (!Objects.equals(snapshot.getUserWorldId(), conversation.getUserWorldId())
                || !Objects.equals(snapshot.getWorldId(), conversation.getWorldId())
                || !Objects.equals(snapshot.getModuleId(), conversation.getModuleId())) {
            throw new UserRequestException("当前跑团与存档的世界或模组不一致");
        }
    }

    private TrpgSaveOverviewVO toOverview(
            TrpgSave save, GroupConversation conversation) {
        TrpgSaveSnapshotDTO snapshot = save.getSnapshot();
        List<GroupReplyPlan> plans = snapshot == null || snapshot.getReplyPlans() == null
                ? List.of() : snapshot.getReplyPlans();
        Long activePlanId = snapshot == null || snapshot.getConversationState() == null
                ? null : snapshot.getConversationState().getActiveReplyPlanId();
        GroupReplyPlan activePlan = plans.stream()
                .filter(plan -> Objects.equals(plan.getId(), activePlanId))
                .findFirst().orElse(null);
        List<CocCharacter> characters = snapshot == null || snapshot.getCharacters() == null
                ? List.of() : snapshot.getCharacters();
        return new TrpgSaveOverviewVO()
                .setId(save.getId())
                .setConversationId(save.getConversationId())
                .setConversationTitle(snapshot != null
                        && snapshot.getConversationState() != null
                        && StringUtils.hasText(snapshot.getConversationState().getTitle())
                        ? snapshot.getConversationState().getTitle()
                        : conversation.getTitle())
                .setRemark(save.getRemark())
                .setSavedAt(save.getSavedAt())
                .setFormatVersion(save.getFormatVersion())
                .setActivePlanSource(activePlan == null ? null : activePlan.getSource())
                .setActiveSceneId(activePlan == null ? null : activePlan.getContextId())
                .setInvestigators(characters.stream()
                        .filter(character -> "PLAYER".equals(
                                character.getActorType())
                                || "BOT".equals(
                                character.getActorType()))
                        .map(this::investigatorState)
                        .toList());
    }

    private TrpgSaveOverviewVO.InvestigatorStateVO investigatorState(
            CocCharacter character) {
        return new TrpgSaveOverviewVO.InvestigatorStateVO()
                .setCharacterId(character.getId())
                .setName(character.getName())
                .setHpCurrent(character.getHpCurrent())
                .setHpMax(character.getHpMax())
                .setSanCurrent(character.getSanCurrent())
                .setSanMax(character.getSanMax())
                .setMpCurrent(character.getMpCurrent())
                .setMpMax(character.getMpMax())
                .setUnconscious(character.getUnconscious())
                .setDying(character.getDying())
                .setDead(character.getDead());
    }

    private String normalizeRemark(TrpgSaveCreateDTO createDTO) {
        if (createDTO == null || !StringUtils.hasText(createDTO.getRemark())) {
            return null;
        }
        String value = createDTO.getRemark().trim();
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
