package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.dto.WorldArchiveDTO;
import com.me.galchat.domain.dto.WorldArchiveImportResultDTO;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldDetail;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.ICharacterTemplateService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.IWorldArchiveService;
import com.me.galchat.service.IWorldDetailService;
import com.me.galchat.service.IWorldTemplateService;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.me.galchat.utils.ImageSecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class WorldArchiveServiceImpl implements IWorldArchiveService {

    private static final int FORMAT_VERSION = 1;

    private final IUserWorldPrefixService userWorldPrefixService;
    private final IWorldTemplateService worldTemplateService;
    private final IWorldDetailService worldDetailService;
    private final ICharacterTemplateService characterTemplateService;

    @Override
    public WorldArchiveDTO exportMyWorld(Long userId, Long userWorldId) {
        UserWorldPrefix userWorld = getMyWorld(userId, userWorldId);
        WorldTemplate worldTemplate = worldTemplateService.getOwnWorldTemplate(userId, userWorld.getWorldId());
        List<WorldDetail> details = worldDetailService.listWorldDetails(userId, worldTemplate.getId());
        List<CharacterTemplate> characters = characterTemplateService.list(new LambdaQueryWrapper<CharacterTemplate>()
                .eq(CharacterTemplate::getWorldId, worldTemplate.getId()));

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

        WorldArchiveDTO.WorldArchive archiveWorld = archive.getWorld();
        WorldTemplate worldTemplate = createWorldTemplate(userId, archiveWorld);

        List<WorldArchiveDTO.WorldDetailArchive> details = emptyIfNull(archive.getDetails());
        for (WorldArchiveDTO.WorldDetailArchive detail : details) {
            validateDetail(detail);
            worldDetailService.createWorldDetail(userId, worldTemplate.getId(), new WorldDetail()
                    .setAbout(trimToNull(detail.getAbout()))
                    .setDetails(detail.getDetails()));
        }

        List<WorldArchiveDTO.CharacterArchive> characters = emptyIfNull(archive.getCharacters());
        for (WorldArchiveDTO.CharacterArchive character : characters) {
            validateCharacter(character);
            characterTemplateService.createCharacterTemplate(userId, worldTemplate.getId(), new CharacterTemplate()
                    .setName(character.getName().trim())
                    .setImage(character.getImage())
                    .setBackground(character.getBackground())
                    .setPersonality(character.getPersonality())
                    .setCocPlayStyle(character.getCocPlayStyle())
                    .setFavorability(character.getFavorability())
                    .setInitFavor(character.getInitFavor()));
        }

        return new WorldArchiveImportResultDTO()
                .setWorldId(worldTemplate.getId())
                .setName(worldTemplate.getName())
                .setDetailCount(details.size())
                .setCharacterCount(characters.size());
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
    }

    private void validateDetail(WorldArchiveDTO.WorldDetailArchive detail) {
        if (detail == null) {
            throw new UserRequestException("世界详情不能为空");
        }
        if (!StringUtils.hasText(detail.getDetails())) {
            throw new UserRequestException("世界详情内容不能为空");
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

    private <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
