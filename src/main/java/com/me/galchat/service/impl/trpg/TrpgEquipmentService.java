package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.CocWeaponCatalogConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpEquipmentDTOs;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgWeaponStash;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.TrpgWeaponStashMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TrpgEquipmentService {

    private final CocCharacterMapper characterMapper;
    private final CocCharacterProfileMapper profileMapper;
    private final CocCharacterWeaponMapper weaponMapper;
    private final TrpgWeaponStashMapper stashMapper;
    private final GroupConversationMapper conversationMapper;
    private final TrpgSceneParticipantService participantService;

    @Transactional(rollbackFor = Exception.class)
    public KpEquipmentDTOs.StashResult stashWeapon(
            Long runId,
            String characterName,
            String weaponName,
            KpEquipmentDTOs.StashReason reason) {
        if (reason == null) {
            throw new UserRequestException("暂存武器原因不能为空");
        }
        GroupConversation conversation = conversationMapper.selectById(
                requirePositiveId(runId, "跑团ID"));
        if (conversation == null
                || !runId.equals(conversation.getId())
                || !GroupChatConstant.MODE_TRPG.equals(
                conversation.getMode())) {
            throw new UserRequestException("跑团不存在");
        }
        CocCharacter owner = requireCharacter(
                runId, characterName);
        String normalizedWeaponName = requireText(
                weaponName, "武器名称");
        List<CocCharacterWeapon> matches = safe(
                weaponMapper.selectByCharacterIdAndNameForUpdate(
                        owner.getId(), normalizedWeaponName));
        if (matches.size() != 1) {
            throw new UserRequestException(matches.isEmpty()
                    ? "人物卡未持有该武器"
                    : "人物卡存在多个同名武器，无法唯一暂存");
        }
        CocCharacterWeapon weapon = matches.getFirst();
        String locationName = participantService.state(conversation)
                .scenePath();
        TrpgWeaponStash stash = new TrpgWeaponStash()
                .setWeaponId(weapon.getId())
                .setRunId(runId)
                .setSourceCharacterName(owner.getName())
                .setLocationName(locationName)
                .setStashReason(reason.name())
                .setWeaponSnapshot(snapshotOf(weapon))
                .setStashedAt(LocalDateTime.now());
        if (stashMapper.insert(stash) != 1) {
            throw new UserRequestException("武器暂存失败");
        }
        if (weaponMapper.deleteById(weapon.getId()) != 1) {
            throw new UserRequestException("原武器状态已变化，请重试");
        }
        return new KpEquipmentDTOs.StashResult(
                weapon.getId(), weapon.getName(), owner.getName(),
                locationName, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public KpEquipmentDTOs.EquipResult equipWeaponFromStash(
            Long runId, Long weaponId, String targetCharacterName) {
        requirePositiveId(runId, "跑团ID");
        requirePositiveId(weaponId, "暂存武器ID");
        TrpgWeaponStash stash =
                stashMapper.selectByRunIdAndWeaponIdForUpdate(
                        runId, weaponId);
        if (stash == null || stash.getWeaponSnapshot() == null) {
            throw new UserRequestException("暂存武器不存在");
        }
        CocCharacter target = requireCharacter(
                runId, targetCharacterName);
        String weaponName = stash.getWeaponSnapshot().getName();
        if (!safe(weaponMapper.selectByCharacterIdAndNameForUpdate(
                target.getId(), weaponName)).isEmpty()) {
            throw new UserRequestException(
                    "目标人物卡已持有同名武器，无法唯一装备");
        }
        CocCharacterWeapon weapon = weaponFromSnapshot(
                weaponId, target.getId(), stash.getWeaponSnapshot());
        if (weaponMapper.insert(weapon) != 1) {
            throw new UserRequestException("武器装备失败");
        }
        if (stashMapper.deleteById(weaponId) != 1) {
            throw new UserRequestException("暂存武器状态已变化，请重试");
        }
        return new KpEquipmentDTOs.EquipResult(
                weaponId, weaponName, target.getName());
    }

    @Transactional(rollbackFor = Exception.class)
    public KpEquipmentDTOs.PurchaseResult purchaseEquipment(
            Long runId, KpEquipmentDTOs.PurchaseRequest request) {
        requirePositiveId(runId, "跑团ID");
        if (request == null || request.entries() == null
                || request.entries().isEmpty()) {
            throw new UserRequestException("购买内容不能为空");
        }
        Map<String, CocCharacter> characters = new HashMap<>();
        Set<String> requestedWeapons = new HashSet<>();
        List<ValidatedPurchase> validated = new ArrayList<>();
        for (KpEquipmentDTOs.PurchaseEntry entry : request.entries()) {
            if (entry == null || entry.type() == null) {
                throw new UserRequestException("购买条目不完整");
            }
            String characterName = requireText(
                    entry.characterName(), "人物卡名称");
            String name = requireText(entry.name(), "物品名称");
            CocCharacter character = characters.computeIfAbsent(
                    characterName,
                    ignored -> requireCharacter(runId, characterName));
            CocWeaponCatalogConstant.WeaponDefinition definition = null;
            if (entry.type() == KpEquipmentDTOs.PurchaseType.WEAPON) {
                definition = CocWeaponCatalogConstant.findByExactName(name)
                        .orElseThrow(() -> new UserRequestException(
                                "武器目录中不存在：" + name));
                String duplicateKey = character.getId() + "\u0000" + name;
                if (!requestedWeapons.add(duplicateKey)
                        || !safe(weaponMapper
                        .selectByCharacterIdAndNameForUpdate(
                                character.getId(), name)).isEmpty()) {
                    throw new UserRequestException(
                            "目标人物卡已持有或重复购买同名武器：" + name);
                }
            }
            validated.add(new ValidatedPurchase(
                    character, entry.type(), name, definition));
        }

        Map<Long, CocCharacterProfile> profiles = new HashMap<>();
        List<KpEquipmentDTOs.PurchaseLineResult> results =
                new ArrayList<>();
        for (ValidatedPurchase entry : validated) {
            if (entry.type() == KpEquipmentDTOs.PurchaseType.WEAPON) {
                CocCharacterWeapon weapon = catalogWeapon(
                        entry.character().getId(), entry.definition());
                if (weaponMapper.insert(weapon) != 1) {
                    throw new UserRequestException("武器添加失败");
                }
            } else {
                CocCharacterProfile profile = profiles.computeIfAbsent(
                        entry.character().getId(), this::requireProfile);
                profile.setEquipmentText(appendEquipment(
                        profile.getEquipmentText(), entry.name()));
                if (profileMapper.updateById(profile) != 1) {
                    throw new UserRequestException("物品栏更新失败");
                }
            }
            results.add(new KpEquipmentDTOs.PurchaseLineResult(
                    entry.character().getName(), entry.type(),
                    entry.name()));
        }
        return new KpEquipmentDTOs.PurchaseResult(List.copyOf(results));
    }

    private CocCharacter requireCharacter(Long runId, String name) {
        String normalizedName = requireText(name, "人物卡名称");
        List<CocCharacter> matches = safe(characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId)
                        .eq(CocCharacter::getName, normalizedName)
                        .orderByAsc(CocCharacter::getId)
                        .last("FOR UPDATE")));
        if (matches.size() != 1) {
            throw new UserRequestException(matches.isEmpty()
                    ? "人物卡不存在：" + normalizedName
                    : "人物卡名称不唯一：" + normalizedName);
        }
        return matches.getFirst();
    }

    private CocCharacterProfile requireProfile(Long characterId) {
        List<CocCharacterProfile> matches = safe(profileMapper.selectList(
                new LambdaQueryWrapper<CocCharacterProfile>()
                        .eq(CocCharacterProfile::getCharacterId,
                                characterId)
                        .last("FOR UPDATE")));
        if (matches.size() != 1) {
            throw new UserRequestException("人物卡物品栏不存在");
        }
        return matches.getFirst();
    }

    private TrpgWeaponStash.WeaponSnapshot snapshotOf(
            CocCharacterWeapon weapon) {
        return new TrpgWeaponStash.WeaponSnapshot()
                .setName(weapon.getName())
                .setSkillName(weapon.getSkillName())
                .setDamage(weapon.getDamage())
                .setRange(weapon.getRange())
                .setAttacksPerRound(weapon.getAttacksPerRound())
                .setAmmoCapacity(weapon.getAmmoCapacity())
                .setRemainingAmmo(weapon.getRemainingAmmo())
                .setMalfunction(weapon.getMalfunction())
                .setCanImpale(weapon.getCanImpale())
                .setIsBroken(weapon.getIsBroken())
                .setAbnormal(weapon.getAbnormal())
                .setRiskTags(weapon.getRiskTags() == null
                        ? null : List.copyOf(weapon.getRiskTags()))
                .setNotes(weapon.getNotes());
    }

    private CocCharacterWeapon weaponFromSnapshot(
            Long weaponId, Long characterId,
            TrpgWeaponStash.WeaponSnapshot snapshot) {
        return new CocCharacterWeapon()
                .setId(weaponId)
                .setCharacterId(characterId)
                .setName(snapshot.getName())
                .setSkillName(snapshot.getSkillName())
                .setDamage(snapshot.getDamage())
                .setRange(snapshot.getRange())
                .setAttacksPerRound(snapshot.getAttacksPerRound())
                .setAmmoCapacity(snapshot.getAmmoCapacity())
                .setRemainingAmmo(snapshot.getRemainingAmmo())
                .setMalfunction(snapshot.getMalfunction())
                .setCanImpale(snapshot.getCanImpale())
                .setIsBroken(snapshot.getIsBroken())
                .setAbnormal(snapshot.getAbnormal())
                .setRiskTags(snapshot.getRiskTags() == null
                        ? null : List.copyOf(snapshot.getRiskTags()))
                .setNotes(snapshot.getNotes());
    }

    private CocCharacterWeapon catalogWeapon(
            Long characterId,
            CocWeaponCatalogConstant.WeaponDefinition definition) {
        return new CocCharacterWeapon()
                .setCharacterId(characterId)
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

    private String appendEquipment(String existing, String item) {
        return StringUtils.hasText(existing)
                ? existing.strip() + "；" + item : item;
    }

    private Long requirePositiveId(Long value, String label) {
        if (value == null || value <= 0) {
            throw new UserRequestException(label + "不合法");
        }
        return value;
    }

    private String requireText(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new UserRequestException(label + "不能为空");
        }
        return value.strip();
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ValidatedPurchase(
            CocCharacter character,
            KpEquipmentDTOs.PurchaseType type,
            String name,
            CocWeaponCatalogConstant.WeaponDefinition definition) {
    }
}
