package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationLockService;
import com.me.galchat.service.impl.group.GroupTurnRecoveryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgSaveCreateDTO;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.TrpgAutoSave;
import com.me.galchat.domain.po.TrpgSave;
import com.me.galchat.domain.vo.TrpgSaveOverviewVO;
import com.me.galchat.domain.vo.TrpgRollbackOverviewVO;
import com.me.galchat.domain.vo.TrpgRollbackResultVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.TrpgAutoSaveMapper;
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
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrpgSaveServiceImpl implements ITrpgSaveService {

    public static final int FORMAT_VERSION = 2;
    public static final String CHECKPOINT_TURN = "TURN";
    public static final String CHECKPOINT_SCENE = "SCENE";
    public static final String CHECKPOINT_INITIAL = "INITIAL";

    private final IUserWorldPrefixService userWorldPrefixService;
    private final GroupConversationMapper conversationMapper;
    private final GroupChatTurnMapper turnMapper;
    private final TrpgSaveMapper saveMapper;
    private final TrpgAutoSaveMapper autoSaveMapper;
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
            transactionTemplate.executeWithoutResult(status -> {
                snapshotService.restoreDatabase(conversation, snapshot);
                autoSaveMapper.deleteAfter(conversationId, save.getSavedAt());
            });
            snapshotService.restoreDerivedState(conversation, snapshot);
        } finally {
            lockService.unlock(lock);
        }
    }

    @Override
    public void saveBeforeTurn(GroupConversation conversation) {
        TrpgSaveSnapshotDTO snapshot = snapshotService.capture(conversation);
        if (snapshot == null
                || !Objects.equals(snapshot.getFormatVersion(), FORMAT_VERSION)) {
            throw new IllegalStateException("跑团自动存档快照格式不正确");
        }
        LocalDateTime savedAt = LocalDateTime.now();
        upsertCheckpoint(conversation.getId(), CHECKPOINT_TURN,
                savedAt, snapshot);
        Long totalTurnCount = turnMapper.selectCount(
                new LambdaQueryWrapper<GroupChatTurn>()
                        .eq(GroupChatTurn::getConversationId,
                                conversation.getId()));
        if ((totalTurnCount == null || totalTurnCount == 0)
                && autoSaveMapper.selectByConversationAndType(
                conversation.getId(), CHECKPOINT_INITIAL) == null) {
            upsertCheckpoint(conversation.getId(), CHECKPOINT_INITIAL,
                    savedAt, snapshot);
        }
        if (snapshot.getConversationState() != null
                && snapshot.getConversationState().getActiveReplyPlanId()
                == null) {
            upsertCheckpoint(conversation.getId(), CHECKPOINT_SCENE,
                    savedAt, snapshot);
        }
    }

    @Override
    public TrpgRollbackOverviewVO getRollbackOverview(
            Long userId, Long conversationId) {
        GroupConversation conversation = requireTrpgConversation(
                userId, conversationId, false);
        Map<String, TrpgAutoSave> checkpoints =
                autoSaveMapper.selectByConversationId(conversationId).stream()
                        .collect(Collectors.toMap(
                                TrpgAutoSave::getCheckpointType,
                                Function.identity(),
                                (first, ignored) -> first));
        TrpgSave manualSave = saveMapper.selectByConversationId(conversationId);
        return new TrpgRollbackOverviewVO()
                .setTurn(rollbackPoint(conversation,
                        checkpoints.get(CHECKPOINT_TURN), manualSave))
                .setScene(rollbackPoint(conversation,
                        checkpoints.get(CHECKPOINT_SCENE), manualSave))
                .setInitial(rollbackPoint(conversation,
                        checkpoints.get(CHECKPOINT_INITIAL), manualSave));
    }

    @Override
    public TrpgRollbackResultVO rollbackTurn(
            Long userId, Long conversationId) {
        return rollback(userId, conversationId, CHECKPOINT_TURN);
    }

    @Override
    public TrpgRollbackResultVO rollbackScene(
            Long userId, Long conversationId) {
        return rollback(userId, conversationId, CHECKPOINT_SCENE);
    }

    @Override
    public TrpgRollbackResultVO rollbackInitial(
            Long userId, Long conversationId) {
        return rollback(userId, conversationId, CHECKPOINT_INITIAL);
    }

    private TrpgRollbackResultVO rollback(
            Long userId, Long conversationId, String checkpointType) {
        GroupConversation conversation = requireTrpgConversation(
                userId, conversationId, true);
        TrpgAutoSave autoSave = autoSaveMapper.selectByConversationAndType(
                conversationId, checkpointType);
        if (autoSave == null) {
            throw new UserRequestException("当前跑团没有可回滚的自动存档点");
        }
        if (!Objects.equals(autoSave.getFormatVersion(), FORMAT_VERSION)) {
            throw new UserRequestException("不支持的跑团自动存档格式");
        }
        TrpgSaveSnapshotDTO snapshot = autoSave.getSnapshot();
        validateSnapshot(conversation, snapshot);
        GroupConversationLockService.OwnedLock lock = requireLock(conversationId);
        try {
            recoveryService.assertConversationHasNoNonTerminalTurns(
                    conversationId);
            Boolean manualSaveDeleted = transactionTemplate.execute(status -> {
                TrpgSave manualSave = saveMapper.selectByConversationId(
                        conversationId);
                boolean deleteManualSave = isAfter(
                        manualSave == null ? null : manualSave.getSavedAt(),
                        autoSave.getSavedAt());
                snapshotService.restoreDatabase(conversation, snapshot);
                if (deleteManualSave) {
                    saveMapper.deleteById(manualSave.getId());
                }
                autoSaveMapper.deleteAfter(
                        conversationId, autoSave.getSavedAt());
                return deleteManualSave;
            });
            snapshotService.restoreDerivedState(conversation, snapshot);
            return new TrpgRollbackResultVO()
                    .setCheckpointType(checkpointType)
                    .setSavedAt(autoSave.getSavedAt())
                    .setManualSaveDeleted(Boolean.TRUE.equals(
                            manualSaveDeleted));
        } finally {
            lockService.unlock(lock);
        }
    }

    private void upsertCheckpoint(
            Long conversationId,
            String checkpointType,
            LocalDateTime savedAt,
            TrpgSaveSnapshotDTO snapshot) {
        autoSaveMapper.upsert(new TrpgAutoSave()
                .setConversationId(conversationId)
                .setCheckpointType(checkpointType)
                .setSavedAt(savedAt)
                .setFormatVersion(FORMAT_VERSION)
                .setSnapshot(snapshot));
    }

    private TrpgRollbackOverviewVO.RollbackPointVO rollbackPoint(
            GroupConversation conversation,
            TrpgAutoSave checkpoint,
            TrpgSave manualSave) {
        boolean available = checkpoint != null
                && validSnapshot(conversation, checkpoint);
        return new TrpgRollbackOverviewVO.RollbackPointVO()
                .setAvailable(available)
                .setSavedAt(available ? checkpoint.getSavedAt() : null)
                .setMessageBoundaryId(available
                        && checkpoint.getSnapshot().getCursors() != null
                        ? checkpoint.getSnapshot().getCursors()
                                .getMaxMessageId()
                        : null)
                .setWillDeleteManualSave(available && isAfter(
                        manualSave == null ? null : manualSave.getSavedAt(),
                        checkpoint.getSavedAt()))
                .setInvestigators(available
                        ? investigatorStates(checkpoint.getSnapshot())
                        : List.of());
    }

    private boolean validSnapshot(
            GroupConversation conversation, TrpgAutoSave checkpoint) {
        TrpgSaveSnapshotDTO snapshot = checkpoint.getSnapshot();
        return Objects.equals(checkpoint.getFormatVersion(), FORMAT_VERSION)
                && snapshot != null
                && Objects.equals(snapshot.getFormatVersion(), FORMAT_VERSION)
                && Objects.equals(snapshot.getConversationId(),
                conversation.getId())
                && Objects.equals(snapshot.getUserWorldId(),
                conversation.getUserWorldId())
                && Objects.equals(snapshot.getWorldId(),
                conversation.getWorldId())
                && Objects.equals(snapshot.getModuleId(),
                conversation.getModuleId());
    }

    private boolean isAfter(
            LocalDateTime candidate, LocalDateTime boundary) {
        return candidate != null && boundary != null
                && candidate.isAfter(boundary);
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
                .setMessageBoundaryId(snapshot != null
                        && snapshot.getCursors() != null
                        ? snapshot.getCursors().getMaxMessageId()
                        : null)
                .setActivePlanSource(activePlan == null ? null : activePlan.getSource())
                .setActiveSceneId(activePlan == null ? null : activePlan.getContextId())
                .setInvestigators(investigatorStates(snapshot));
    }

    private List<TrpgSaveOverviewVO.InvestigatorStateVO> investigatorStates(
            TrpgSaveSnapshotDTO snapshot) {
        List<CocCharacter> characters = snapshot == null
                || snapshot.getCharacters() == null
                ? List.of() : snapshot.getCharacters();
        return characters.stream()
                        .filter(character -> "PLAYER".equals(
                                character.getActorType())
                                || "BOT".equals(
                                character.getActorType()))
                        .map(this::investigatorState)
                        .toList();
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
