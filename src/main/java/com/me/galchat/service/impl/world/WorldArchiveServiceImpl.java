package com.me.galchat.service.impl.world;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.dto.WorldArchiveDTO;
import com.me.galchat.domain.dto.WorldArchiveImportResultDTO;
import com.me.galchat.domain.dto.WorldArchiveReplaceResultDTO;
import com.me.galchat.domain.dto.WorldTemplateUsageDTO;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldDetail;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.VectorStoreCleanupMapper;
import com.me.galchat.mapper.WorldTemplateMapper;
import com.me.galchat.service.ICharacterTemplateService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.IWorldArchiveService;
import com.me.galchat.service.IWorldDetailService;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.utils.ImageSecurityUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class WorldArchiveServiceImpl implements IWorldArchiveService {

    private static final int FORMAT_VERSION = 1;
    private static final double MINIMUM_DIRECT_REPLACE_MATCH_RATE = 0.5;

    private final IUserWorldPrefixService userWorldPrefixService;
    private final IWorldTemplateService worldTemplateService;
    private final IWorldDetailService worldDetailService;
    private final ICharacterTemplateService characterTemplateService;
    private final WorldTemplateMapper worldTemplateMapper;
    private final VectorStoreCleanupMapper vectorStoreCleanupMapper;
    private final CacheManager cacheManager;

    @Override
    public WorldArchiveDTO exportMyWorld(Long userId, Long userWorldId) {
        UserWorldPrefix userWorld = getMyWorld(userId, userWorldId);
        WorldTemplate worldTemplate = worldTemplateService.getOwnWorldTemplate(userId, userWorld.getWorldId());
        List<WorldDetail> details = worldDetailService.listWorldDetails(userId, worldTemplate.getId());
        List<CharacterTemplate> characters = listCharacters(worldTemplate.getId());

        return new WorldArchiveDTO()
                .setFormatVersion(FORMAT_VERSION)
                .setWorld(toArchiveWorld(worldTemplate))
                .setDetails(details.stream().map(this::toArchiveDetail).toList())
                .setCharacters(characters.stream().map(this::toArchiveCharacter).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorldArchiveImportResultDTO importWorld(Long userId, WorldArchiveDTO archive) {
        validateArchive(archive);

        WorldTemplate worldTemplate = createWorldTemplate(userId, archive.getWorld());
        List<WorldArchiveDTO.WorldDetailArchive> details = emptyIfNull(archive.getDetails());
        for (WorldArchiveDTO.WorldDetailArchive detail : details) {
            worldDetailService.createWorldDetail(userId, worldTemplate.getId(), toWorldDetail(detail));
        }

        List<WorldArchiveDTO.CharacterArchive> characters = emptyIfNull(archive.getCharacters());
        for (WorldArchiveDTO.CharacterArchive character : characters) {
            characterTemplateService.createCharacterTemplate(
                    userId, worldTemplate.getId(), toCharacterTemplate(character));
        }

        return new WorldArchiveImportResultDTO()
                .setWorldId(worldTemplate.getId())
                .setName(worldTemplate.getName())
                .setDetailCount(details.size())
                .setCharacterCount(characters.size());
    }

    @Override
    public WorldTemplateUsageDTO getWorldTemplateUsage(Long userId, Long worldId) {
        worldTemplateService.getOwnWorldTemplate(userId, worldId);
        long associatedWorldCount = countAssociatedWorlds(worldId);
        return new WorldTemplateUsageDTO()
                .setAssociatedWorldCount(associatedWorldCount)
                .setDeletable(associatedWorldCount == 0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "worldPrompt", key = "#worldId")
    public void deleteWorldTemplate(Long userId, Long worldId) {
        worldTemplateService.getOwnWorldTemplate(userId, worldId);
        if (countAssociatedWorlds(worldId) > 0) {
            throw new UserRequestException("当前模板仍有关联世界，不能删除");
        }

        List<CharacterTemplate> characters = listCharacters(worldId);
        vectorStoreCleanupMapper.deleteWorldDetailsByWorldId(worldId);
        worldDetailService.remove(new LambdaQueryWrapper<WorldDetail>()
                .eq(WorldDetail::getWorldId, worldId));
        characterTemplateService.remove(new LambdaQueryWrapper<CharacterTemplate>()
                .eq(CharacterTemplate::getWorldId, worldId));

        int deleted = worldTemplateMapper.deleteOwnedTemplateIfUnused(worldId, userId);
        if (deleted == 0) {
            throw new UserRequestException("当前模板仍有关联世界，不能删除");
        }
        evictCharacterTemplates(characters);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorldArchiveReplaceResultDTO replaceWorldTemplate(Long userId, Long worldId,
                                                             WorldArchiveDTO archive, boolean confirmLowMatch) {
        WorldTemplate oldWorld = worldTemplateService.getOwnWorldTemplate(userId, worldId);
        validateArchive(archive);

        List<CharacterTemplate> oldCharacters = listCharacters(worldId);
        ReplacementPlan plan = buildReplacementPlan(oldCharacters, emptyIfNull(archive.getCharacters()));
        WorldArchiveReplaceResultDTO result = replacementResult(oldWorld, archive, plan);
        if (plan.matchRate() < MINIMUM_DIRECT_REPLACE_MATCH_RATE && !confirmLowMatch) {
            return result.setConfirmationRequired(true).setReplaced(false);
        }

        WorldArchiveDTO.WorldArchive archiveWorld = archive.getWorld();
        worldTemplateService.updateWorldTemplate(userId, worldId, new WorldTemplate()
                .setName(archiveWorld.getName().trim())
                .setImage(archiveWorld.getImage())
                .setAuthor(archiveWorld.getAuthor())
                .setBackground(archiveWorld.getBackground())
                .setVisible(archiveWorld.getVisible()));

        vectorStoreCleanupMapper.deleteWorldDetailsByWorldId(worldId);
        worldDetailService.remove(new LambdaQueryWrapper<WorldDetail>()
                .eq(WorldDetail::getWorldId, worldId));
        for (WorldArchiveDTO.WorldDetailArchive detail : emptyIfNull(archive.getDetails())) {
            worldDetailService.createWorldDetail(userId, worldId, toWorldDetail(detail));
        }

        for (WorldArchiveDTO.CharacterArchive character : emptyIfNull(archive.getCharacters())) {
            CharacterTemplate replacement = toCharacterTemplate(character);
            CharacterTemplate matched = plan.oldCharactersByName().get(normalizeCharacterName(character.getName()));
            if (matched == null) {
                characterTemplateService.createCharacterTemplate(userId, worldId, replacement);
            } else {
                characterTemplateService.updateCharacterTemplate(userId, worldId, matched.getId(), replacement);
            }
        }

        return result.setConfirmationRequired(false).setReplaced(true);
    }

    private ReplacementPlan buildReplacementPlan(List<CharacterTemplate> oldCharacters,
                                                  List<WorldArchiveDTO.CharacterArchive> newCharacters) {
        Map<String, CharacterTemplate> oldByName = new LinkedHashMap<>();
        for (CharacterTemplate character : oldCharacters) {
            String normalizedName = normalizeCharacterName(character.getName());
            if (oldByName.putIfAbsent(normalizedName, character) != null) {
                throw new UserRequestException("当前模板存在重名角色，无法按名称替换：" + character.getName().trim());
            }
        }

        Map<String, WorldArchiveDTO.CharacterArchive> newByName = new LinkedHashMap<>();
        for (WorldArchiveDTO.CharacterArchive character : newCharacters) {
            String normalizedName = normalizeCharacterName(character.getName());
            if (newByName.putIfAbsent(normalizedName, character) != null) {
                throw new UserRequestException("上传模板存在重名角色，无法按名称替换：" + character.getName().trim());
            }
        }

        List<String> matchedNames = new ArrayList<>();
        List<String> unchangedNames = new ArrayList<>();
        for (Map.Entry<String, CharacterTemplate> entry : oldByName.entrySet()) {
            if (newByName.containsKey(entry.getKey())) {
                matchedNames.add(entry.getValue().getName());
            } else {
                unchangedNames.add(entry.getValue().getName());
            }
        }
        List<String> addedNames = newByName.entrySet().stream()
                .filter(entry -> !oldByName.containsKey(entry.getKey()))
                .map(entry -> entry.getValue().getName().trim())
                .toList();
        double matchRate = oldCharacters.isEmpty()
                ? 1.0
                : (double) matchedNames.size() / oldCharacters.size();
        return new ReplacementPlan(oldByName, matchedNames, addedNames, unchangedNames, matchRate);
    }

    private WorldArchiveReplaceResultDTO replacementResult(WorldTemplate oldWorld, WorldArchiveDTO archive,
                                                            ReplacementPlan plan) {
        return new WorldArchiveReplaceResultDTO()
                .setWorldId(oldWorld.getId())
                .setName(archive.getWorld().getName().trim())
                .setDetailCount(emptyIfNull(archive.getDetails()).size())
                .setCharacterCount(emptyIfNull(archive.getCharacters()).size())
                .setMatchedCharacterCount(plan.matchedNames().size())
                .setAddedCharacterCount(plan.addedNames().size())
                .setUnchangedCharacterCount(plan.unchangedNames().size())
                .setMatchRate(plan.matchRate())
                .setMatchedCharacterNames(plan.matchedNames())
                .setAddedCharacterNames(plan.addedNames())
                .setUnchangedCharacterNames(plan.unchangedNames());
    }

    private long countAssociatedWorlds(Long worldId) {
        return userWorldPrefixService.count(new LambdaQueryWrapper<UserWorldPrefix>()
                .eq(UserWorldPrefix::getWorldId, worldId));
    }

    private List<CharacterTemplate> listCharacters(Long worldId) {
        return characterTemplateService.list(new LambdaQueryWrapper<CharacterTemplate>()
                .eq(CharacterTemplate::getWorldId, worldId));
    }

    private void evictCharacterTemplates(List<CharacterTemplate> characters) {
        Cache cache = cacheManager.getCache("characterTemplate");
        if (cache == null) {
            return;
        }
        characters.stream()
                .map(CharacterTemplate::getId)
                .filter(Objects::nonNull)
                .forEach(cache::evict);
    }

    private UserWorldPrefix getMyWorld(Long userId, Long userWorldId) {
        UserWorldPrefix userWorld = userWorldPrefixService.checkUserWorldAuth(userId, userWorldId, true);
        if (!Boolean.TRUE.equals(userWorld.getMyWorld())) {
            throw new UserAuthException("无权导出该世界模板");
        }
        return userWorld;
    }

    private WorldArchiveDTO.WorldArchive toArchiveWorld(WorldTemplate worldTemplate) {
        return new WorldArchiveDTO.WorldArchive()
                .setName(worldTemplate.getName())
                .setImage(worldTemplate.getImage())
                .setAuthor(worldTemplate.getAuthor())
                .setBackground(worldTemplate.getBackground())
                .setVisible(worldTemplate.getVisible());
    }

    private WorldArchiveDTO.WorldDetailArchive toArchiveDetail(WorldDetail detail) {
        return new WorldArchiveDTO.WorldDetailArchive()
                .setAbout(detail.getAbout())
                .setDetails(detail.getDetails());
    }

    private WorldArchiveDTO.CharacterArchive toArchiveCharacter(CharacterTemplate character) {
        return new WorldArchiveDTO.CharacterArchive()
                .setName(character.getName())
                .setImage(character.getImage())
                .setBackground(character.getBackground())
                .setPersonality(character.getPersonality())
                .setCocPlayStyle(character.getCocPlayStyle())
                .setFavorability(character.getFavorability())
                .setInitFavor(character.getInitFavor());
    }

    private void validateArchive(WorldArchiveDTO archive) {
        if (archive == null) {
            throw new UserRequestException("导入内容不能为空");
        }
        if (!Objects.equals(archive.getFormatVersion(), FORMAT_VERSION)) {
            throw new UserRequestException("不支持的世界导入格式");
        }
        if (archive.getWorld() == null) {
            throw new UserRequestException("世界模板不能为空");
        }
        if (!StringUtils.hasText(archive.getWorld().getName())) {
            throw new UserRequestException("世界名称不能为空");
        }
        if (!StringUtils.hasText(archive.getWorld().getBackground())) {
            throw new UserRequestException("世界背景不能为空");
        }
        emptyIfNull(archive.getDetails()).forEach(this::validateDetail);
        emptyIfNull(archive.getCharacters()).forEach(this::validateCharacter);
    }

    private void validateDetail(WorldArchiveDTO.WorldDetailArchive detail) {
        if (detail == null) {
            throw new UserRequestException("世界详情不能为空");
        }
        if (!StringUtils.hasText(detail.getDetails())) {
            throw new UserRequestException("世界详情内容不能为空");
        }
        if (detail.getDetails().length() > 2000) {
            throw new UserRequestException("世界详情内容过长，不能超过2000字");
        }
    }

    private void validateCharacter(WorldArchiveDTO.CharacterArchive character) {
        if (character == null) {
            throw new UserRequestException("角色模板不能为空");
        }
        if (!StringUtils.hasText(character.getName())) {
            throw new UserRequestException("角色名称不能为空");
        }
    }

    private WorldTemplate createWorldTemplate(Long userId, WorldArchiveDTO.WorldArchive archiveWorld) {
        WorldTemplate worldTemplate = new WorldTemplate()
                .setName(archiveWorld.getName().trim())
                .setImage(ImageSecurityUtils.normalizeOssImageUrl(archiveWorld.getImage()))
                .setAuthor(archiveWorld.getAuthor())
                .setAuthorId(userId)
                .setBackground(archiveWorld.getBackground())
                .setVisible(!Boolean.FALSE.equals(archiveWorld.getVisible()));
        worldTemplateService.save(worldTemplate);
        return worldTemplate;
    }

    private WorldDetail toWorldDetail(WorldArchiveDTO.WorldDetailArchive detail) {
        return new WorldDetail()
                .setAbout(trimToNull(detail.getAbout()))
                .setDetails(detail.getDetails());
    }

    private CharacterTemplate toCharacterTemplate(WorldArchiveDTO.CharacterArchive character) {
        return new CharacterTemplate()
                .setName(character.getName().trim())
                .setImage(character.getImage())
                .setBackground(character.getBackground())
                .setPersonality(character.getPersonality())
                .setCocPlayStyle(character.getCocPlayStyle())
                .setFavorability(character.getFavorability())
                .setInitFavor(character.getInitFavor());
    }

    private String normalizeCharacterName(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private record ReplacementPlan(Map<String, CharacterTemplate> oldCharactersByName,
                                   List<String> matchedNames,
                                   List<String> addedNames,
                                   List<String> unchangedNames,
                                   double matchRate) {
    }
}
