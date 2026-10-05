package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.domain.dto.KpToolStateUndo;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TrpgToolStateRecoveryService {
    public static final Set<String> TOOLS = Set.of("updateQuickNotes", "updateWeaponState", "stashWeapon",
            "equipWeaponFromStash", "updateCombatStates");
    private final CocCharacterMapper characters;
    private final CocCharacterWeaponMapper weapons;
    private final TrpgWeaponStashMapper stashes;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Exception.class)
    public void rollback(Long runId, String tool, String result) {
        if (result == null) throw new IllegalStateException("工具记录缺少撤销数据");
        // Error responses have no state changes; legacy successful responses must not be silently discarded.
        if (!result.stripLeading().startsWith("{")) {
            if (result.contains("快速笔记已更新")) throw new IllegalStateException("工具记录缺少撤销数据");
            return;
        }
        var undoNode = json.readTree(result).get("undo");
        if (undoNode == null || undoNode.isNull()) throw new IllegalStateException("工具记录缺少撤销数据");
        var undo = json.treeToValue(undoNode, KpToolStateUndo.class);
        if (!Objects.equals(runId, undo.runId())) throw new IllegalStateException("工具撤销跑团不匹配");
        if ("updateQuickNotes".equals(tool) || "updateCombatStates".equals(tool)) {
            if (undo.cards() == null || undo.cards().isEmpty()) throw new IllegalStateException("人物状态撤销记录不完整");
            for (var change : undo.cards()) {
                var before = change.before(); var after = change.after();
                if (before == null || after == null || !Objects.equals(before.id(), after.id())) throw new IllegalStateException("人物状态撤销记录不完整");
                CocCharacter card = characters.selectById(before.id());
                if (card == null || !Objects.equals(card.getRunId(), runId)) throw new IllegalStateException("人物状态上下文已变化");
                var update = new LambdaUpdateWrapper<CocCharacter>().eq(CocCharacter::getId, card.getId())
                        .eq(CocCharacter::getRunId, runId);
                if ("updateQuickNotes".equals(tool)) {
                    requireEqual(card.getQuickNotes(), after.quickNotes());
                    update.set(CocCharacter::getQuickNotes, before.quickNotes());
                } else {
                    requireEqual(card.getInCover(), after.inCover());
                    requireEqual(card.getCoverActionForfeitPending(), after.coverActionForfeitPending());
                    requireEqual(card.getRestrainedByCharacterId(), after.restrainedByCharacterId());
                    update.set(CocCharacter::getInCover, before.inCover())
                            .set(CocCharacter::getCoverActionForfeitPending, before.coverActionForfeitPending())
                            .set(CocCharacter::getRestrainedByCharacterId, before.restrainedByCharacterId());
                }
                update.set(CocCharacter::getUpdatedAt, before.updatedAt());
                requireWrite(characters.update(null, update));
            }
            return;
        }
        var change = undo.weapon();
        if (change == null || (change.before() == null && change.after() == null)) throw new IllegalStateException("武器撤销记录不完整");
        Long id = change.before() == null ? change.after().getId() : change.before().getId();
        for (var w : new CocCharacterWeapon[]{change.before(), change.after()}) {
            if (w != null) {
                CocCharacter owner = characters.selectById(w.getCharacterId());
                if (!Objects.equals(id, w.getId()) || owner == null || !Objects.equals(runId, owner.getRunId())) throw new IllegalStateException("武器上下文已变化");
            }
        }
        for (var stash : new TrpgWeaponStash[]{change.stashBefore(), change.stashAfter()}) {
            if (stash != null && (!Objects.equals(stash.getRunId(), runId) || !Objects.equals(stash.getWeaponId(), id))) throw new IllegalStateException("暂存武器上下文已变化");
        }
        requireEqual(weapons.selectById(id), change.after());
        var currentStash = stashes.selectById(id);
        var expectedStash = change.stashAfter();
        if (currentStash == null || expectedStash == null) requireEqual(currentStash, expectedStash);
        else {
            requireEqual(currentStash.getWeaponId(), expectedStash.getWeaponId());
            requireEqual(currentStash.getRunId(), expectedStash.getRunId());
            requireEqual(currentStash.getSourceCharacterName(), expectedStash.getSourceCharacterName());
            requireEqual(currentStash.getLocationName(), expectedStash.getLocationName());
            requireEqual(currentStash.getStashReason(), expectedStash.getStashReason());
            requireEqual(currentStash.getWeaponSnapshot(), expectedStash.getWeaponSnapshot());
        }
        if ("updateWeaponState".equals(tool)) {
            if (change.before() == null || change.after() == null) throw new IllegalStateException("武器状态撤销记录不完整");
            requireWrite(weapons.update(null, new LambdaUpdateWrapper<CocCharacterWeapon>()
                    .eq(CocCharacterWeapon::getId, id)
                    .set(CocCharacterWeapon::getRemainingAmmo, change.before().getRemainingAmmo())
                    .set(CocCharacterWeapon::getIsBroken, change.before().getIsBroken())));
        } else {
            if (change.after() != null) requireWrite(weapons.deleteById(id));
            if (change.stashAfter() != null) requireWrite(stashes.deleteById(id));
            if (change.before() != null) requireWrite(weapons.insert(change.before()));
            if (change.stashBefore() != null) requireWrite(stashes.insert(change.stashBefore()));
        }
    }
    private void requireEqual(Object current, Object expected) {
        if (!Objects.equals(current, expected)) throw new IllegalStateException("工具状态已变化，无法安全回滚");
    }
    private void requireWrite(int count) {
        if (count != 1) throw new IllegalStateException("工具状态回滚失败");
    }
}
