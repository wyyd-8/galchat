package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.domain.dto.CocModuleCreateDTO;
import com.me.galchat.domain.dto.CocModuleArchiveDTO;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocModuleCharacter;
import com.me.galchat.domain.po.CocModuleClue;
import com.me.galchat.domain.po.CocModuleContext;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.CocModuleMaterial;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CocModuleDetailVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocModuleCharacterMapper;
import com.me.galchat.mapper.CocModuleClueMapper;
import com.me.galchat.mapper.CocModuleContextMapper;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.CocModuleMaterialMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CocModuleService {

    private static final int ARCHIVE_FORMAT_VERSION = 1;
    private static final Pattern DAMAGE_BONUS = Pattern.compile(
            "(?:-[12]|0|\\+?(?:1D4|[1-9]\\d*D6))");

    private final CocModuleMapper moduleMapper;
    private final CocModuleContextMapper contextMapper;
    private final CocModuleLocationMapper locationMapper;
    private final CocModuleClueMapper clueMapper;
    private final CocModuleMaterialMapper materialMapper;
    private final GroupConversationMapper conversationMapper;
    private final CocModuleLockService lockService;
    private final CocModuleCharacterMapper moduleCharacterMapper;

    public List<CocModule> listVisible() {
        return listVisible(null);
    }

    public List<CocModule> listVisible(Long userId) {
        return moduleMapper.selectList(
                        new LambdaQueryWrapper<CocModule>()
                                .eq(CocModule::getVisible, true)
                                .orderByDesc(CocModule::getId))
                .stream()
                .filter(module -> Boolean.TRUE.equals(module.getVisible())
                        && (module.getOwnerUserId() == null
                        || Objects.equals(module.getOwnerUserId(), userId)))
                .toList();
    }

    public CocModule getVisible(Long moduleId) {
        return getVisible(null, moduleId);
    }

    public CocModule getVisible(Long userId, Long moduleId) {
        if (moduleId == null) {
            throw new UserRequestException("模组id不能为空");
        }
        CocModule module = moduleMapper.selectById(moduleId);
        if (module == null || !Boolean.TRUE.equals(module.getVisible())
                || module.getOwnerUserId() != null
                && !Objects.equals(module.getOwnerUserId(), userId)) {
            throw new UserRequestException("模组不存在或不可选");
        }
        return module;
    }

    public List<CocModule> listOwned(Long userId) {
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        return safe(moduleMapper.selectList(
                new LambdaQueryWrapper<CocModule>()
                        .eq(CocModule::getOwnerUserId, userId)
                        .orderByDesc(CocModule::getId)));
    }

    public CocModuleDetailVO getReadableDetail(Long userId, Long moduleId) {
        CocModule module = requireReadable(userId, moduleId);
        List<CocModuleContext> contexts = safe(contextMapper.selectList(
                new LambdaQueryWrapper<CocModuleContext>()
                        .eq(CocModuleContext::getModuleId, moduleId)
                        .last("limit 1")));
        return new CocModuleDetailVO()
                .setModule(module)
                .setContext(contexts.isEmpty() ? null : contexts.getFirst())
                .setLocations(safe(locationMapper.selectList(
                        new LambdaQueryWrapper<CocModuleLocation>()
                                .eq(CocModuleLocation::getModuleId, moduleId)
                                .orderByAsc(CocModuleLocation::getId))))
                .setClues(safe(clueMapper.selectList(
                        new LambdaQueryWrapper<CocModuleClue>()
                                .eq(CocModuleClue::getModuleId, moduleId)
                                .orderByAsc(CocModuleClue::getId))))
                .setMaterials(safe(materialMapper.selectList(
                        new LambdaQueryWrapper<CocModuleMaterial>()
                                .eq(CocModuleMaterial::getModuleId, moduleId)
                                .orderByAsc(CocModuleMaterial::getId))))
                .setCharacters(safe(moduleCharacterMapper.selectList(
                        new LambdaQueryWrapper<CocModuleCharacter>()
                                .eq(CocModuleCharacter::getModuleId, moduleId)
                                .orderByAsc(CocModuleCharacter::getSortOrder)
                                .orderByAsc(CocModuleCharacter::getId))));
    }

    public CocModuleArchiveDTO exportReadable(Long userId, Long moduleId) {
        CocModuleDetailVO detail = getReadableDetail(userId, moduleId);
        return new CocModuleArchiveDTO()
                .setFormatVersion(ARCHIVE_FORMAT_VERSION)
                .setModule(toCreateDTO(detail));
    }

    @Transactional(rollbackFor = Exception.class)
    public CocModule importOwned(Long userId, CocModuleArchiveDTO archive) {
        if (archive == null || !Objects.equals(
                archive.getFormatVersion(), ARCHIVE_FORMAT_VERSION)) {
            throw new UserRequestException("不支持的模组导入版本");
        }
        return createOwned(userId, archive.getModule());
    }

    @Transactional(rollbackFor = Exception.class)
    public CocModule create(CocModuleCreateDTO request) {
        return createInternal(null, request);
    }

    @Transactional(rollbackFor = Exception.class)
    public CocModule createOwned(Long userId, CocModuleCreateDTO request) {
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        return createInternal(userId, request);
    }

    private CocModule createInternal(Long ownerUserId,
                                     CocModuleCreateDTO request) {
        validateCreate(request);
        LocalDateTime now = LocalDateTime.now();
        CocModule module = new CocModule()
                .setName(request.getName().trim())
                .setAuthor(trimToNull(request.getAuthor()))
                .setEra(trimToNull(request.getEra()))
                .setIntroduction(request.getIntroduction().trim())
                .setInvestigatorCreation(trimToNull(request.getInvestigatorCreation()))
                .setCoverUrl(trimToNull(request.getCoverUrl()))
                .setPlayerCount(trimToNull(request.getPlayerCount()))
                .setEstimatedDuration(trimToNull(request.getEstimatedDuration()))
                .setVisible(request.getVisible() == null || request.getVisible())
                .setOwnerUserId(ownerUserId)
                .setEditLocked(false)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        moduleMapper.insert(module);
        insertContext(module.getId(), request.getContext(), now);
        insertLocations(module.getId(), safe(request.getLocations()), now);
        insertClues(module.getId(), safe(request.getClues()), now);
        insertMaterials(module.getId(), safe(request.getMaterials()), now);
        insertCharacters(
                module.getId(), safe(request.getCharacters()), now);
        return module;
    }

    @Transactional(rollbackFor = Exception.class)
    public CocModule updateOwned(Long userId, Long moduleId,
                                 CocModuleCreateDTO request) {
        CocModule module = requireOwned(userId, moduleId);
        if (Boolean.TRUE.equals(module.getEditLocked())) {
            throw new UserRequestException("模组已进入受限编辑状态");
        }
        validateCreate(request);
        CocModuleLockService.OwnedLock lock =
                lockService.tryWriteLock(moduleId);
        if (lock == null) {
            throw new UserRequestException("当前模组正在变更，请稍后再编辑");
        }
        boolean unlockAfterTransaction =
                registerUnlockAfterTransaction(lock);
        try {
            module = requireOwned(userId, moduleId);
            if (Boolean.TRUE.equals(module.getEditLocked())) {
                throw new UserRequestException("模组已进入受限编辑状态");
            }
            LocalDateTime now = LocalDateTime.now();
            module.setName(request.getName().trim())
                    .setAuthor(trimToNull(request.getAuthor()))
                    .setEra(trimToNull(request.getEra()))
                    .setIntroduction(request.getIntroduction().trim())
                    .setInvestigatorCreation(trimToNull(
                            request.getInvestigatorCreation()))
                    .setCoverUrl(trimToNull(request.getCoverUrl()))
                    .setPlayerCount(trimToNull(request.getPlayerCount()))
                    .setEstimatedDuration(trimToNull(
                            request.getEstimatedDuration()))
                    .setVisible(request.getVisible() == null
                            ? module.getVisible() : request.getVisible())
                    .setUpdatedAt(now);
            moduleMapper.update(new LambdaUpdateWrapper<CocModule>()
                    .eq(CocModule::getId, moduleId)
                    .set(CocModule::getName, module.getName())
                    .set(CocModule::getAuthor, module.getAuthor())
                    .set(CocModule::getEra, module.getEra())
                    .set(CocModule::getIntroduction,
                            module.getIntroduction())
                    .set(CocModule::getInvestigatorCreation,
                            module.getInvestigatorCreation())
                    .set(CocModule::getCoverUrl, module.getCoverUrl())
                    .set(CocModule::getPlayerCount,
                            module.getPlayerCount())
                    .set(CocModule::getEstimatedDuration,
                            module.getEstimatedDuration())
                    .set(CocModule::getVisible, module.getVisible())
                    .set(CocModule::getUpdatedAt, now));
            replaceChildren(moduleId, request, now);
            return module;
        } finally {
            if (!unlockAfterTransaction) {
                lockService.unlock(lock);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public CocModuleLocation updateLocationContent(
            Long userId, Long moduleId, Long locationId, String content) {
        requireOwned(userId, moduleId);
        if (!StringUtils.hasText(content)) {
            throw new UserRequestException("地点原文不能为空");
        }
        CocModuleLocation location = locationMapper.selectById(locationId);
        if (location == null || !Objects.equals(
                location.getModuleId(), moduleId)) {
            throw new UserRequestException("地点不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        location.setContent(content.trim()).setUpdatedAt(now);
        locationMapper.updateById(location);
        touchModule(moduleId, now);
        return location;
    }

    @Transactional(rollbackFor = Exception.class)
    public CocModuleClue addClue(Long userId, Long moduleId,
                                 CocModuleCreateDTO.Clue request) {
        requireOwned(userId, moduleId);
        if (request == null || !StringUtils.hasText(request.getTitle())
                || !StringUtils.hasText(request.getContent())) {
            throw new UserRequestException("线索标题和原文不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        CocModuleClue clue = new CocModuleClue()
                .setModuleId(moduleId)
                .setTitle(request.getTitle().trim())
                .setContent(request.getContent().trim())
                .setImportant(Boolean.TRUE.equals(request.getImportant()))
                .setCreatedAt(now)
                .setUpdatedAt(now);
        clueMapper.insert(clue);
        touchModule(moduleId, now);
        return clue;
    }

    @Transactional(rollbackFor = Exception.class)
    public CocModuleClue updateClueContent(
            Long userId, Long moduleId, Long clueId, String content) {
        requireOwned(userId, moduleId);
        if (!StringUtils.hasText(content)) {
            throw new UserRequestException("线索原文不能为空");
        }
        CocModuleClue clue = clueMapper.selectById(clueId);
        if (clue == null || !Objects.equals(clue.getModuleId(), moduleId)) {
            throw new UserRequestException("线索不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        clue.setContent(content.trim()).setUpdatedAt(now);
        clueMapper.updateById(clue);
        touchModule(moduleId, now);
        return clue;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteOwned(Long userId, Long moduleId) {
        CocModule module = requireOwned(userId, moduleId);
        if (Boolean.TRUE.equals(module.getEditLocked())) {
            throw new UserRequestException("模组已进入受限编辑状态");
        }
        delete(moduleId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long moduleId) {
        if (moduleId == null) {
            throw new UserRequestException("模组id不能为空");
        }
        CocModuleLockService.OwnedLock lock = lockService.tryWriteLock(moduleId);
        if (lock == null) {
            throw new UserRequestException("当前模组正在被跑团绑定，请稍后再删除");
        }
        boolean unlockAfterTransaction = registerUnlockAfterTransaction(lock);
        try {
            if (moduleMapper.selectById(moduleId) == null) {
                throw new UserRequestException("模组不存在");
            }
            Long referenceCount = conversationMapper.selectCount(
                    new LambdaQueryWrapper<GroupConversation>()
                            .eq(GroupConversation::getModuleId, moduleId));
            if (referenceCount != null && referenceCount > 0) {
                throw new UserRequestException("已有跑团依赖当前模组，不能删除");
            }
            contextMapper.delete(new LambdaQueryWrapper<CocModuleContext>()
                    .eq(CocModuleContext::getModuleId, moduleId));
            locationMapper.delete(new LambdaQueryWrapper<CocModuleLocation>()
                    .eq(CocModuleLocation::getModuleId, moduleId));
            clueMapper.delete(new LambdaQueryWrapper<CocModuleClue>()
                    .eq(CocModuleClue::getModuleId, moduleId));
            materialMapper.delete(new LambdaQueryWrapper<CocModuleMaterial>()
                    .eq(CocModuleMaterial::getModuleId, moduleId));
            moduleCharacterMapper.delete(
                    new LambdaQueryWrapper<CocModuleCharacter>()
                            .eq(CocModuleCharacter::getModuleId,
                                    moduleId));
            moduleMapper.deleteById(moduleId);
        } finally {
            if (!unlockAfterTransaction) {
                lockService.unlock(lock);
            }
        }
    }

    private boolean registerUnlockAfterTransaction(CocModuleLockService.OwnedLock lock) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                lockService.unlock(lock);
            }
        });
        return true;
    }

    private CocModule requireOwned(Long userId, Long moduleId) {
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        if (moduleId == null) {
            throw new UserRequestException("模组id不能为空");
        }
        CocModule module = moduleMapper.selectById(moduleId);
        if (module == null || !Objects.equals(
                module.getOwnerUserId(), userId)) {
            throw new UserRequestException("模组不存在或无权操作");
        }
        return module;
    }

    private CocModule requireReadable(Long userId, Long moduleId) {
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        if (moduleId == null) {
            throw new UserRequestException("模组id不能为空");
        }
        CocModule module = moduleMapper.selectById(moduleId);
        boolean defaultModule = module != null
                && module.getOwnerUserId() == null
                && Boolean.TRUE.equals(module.getVisible());
        boolean ownedModule = module != null && Objects.equals(
                module.getOwnerUserId(), userId);
        if (!defaultModule && !ownedModule) {
            throw new UserRequestException("模组不存在或无权查看");
        }
        return module;
    }

    private void replaceChildren(Long moduleId, CocModuleCreateDTO request,
                                 LocalDateTime now) {
        contextMapper.delete(new LambdaQueryWrapper<CocModuleContext>()
                .eq(CocModuleContext::getModuleId, moduleId));
        locationMapper.delete(new LambdaQueryWrapper<CocModuleLocation>()
                .eq(CocModuleLocation::getModuleId, moduleId));
        clueMapper.delete(new LambdaQueryWrapper<CocModuleClue>()
                .eq(CocModuleClue::getModuleId, moduleId));
        materialMapper.delete(new LambdaQueryWrapper<CocModuleMaterial>()
                .eq(CocModuleMaterial::getModuleId, moduleId));
        moduleCharacterMapper.delete(
                new LambdaQueryWrapper<CocModuleCharacter>()
                        .eq(CocModuleCharacter::getModuleId, moduleId));
        insertContext(moduleId, request.getContext(), now);
        insertLocations(moduleId, safe(request.getLocations()), now);
        insertClues(moduleId, safe(request.getClues()), now);
        insertMaterials(moduleId, safe(request.getMaterials()), now);
        insertCharacters(moduleId, safe(request.getCharacters()), now);
    }

    private void touchModule(Long moduleId, LocalDateTime now) {
        moduleMapper.updateById(new CocModule()
                .setId(moduleId)
                .setUpdatedAt(now));
    }

    private CocModuleCreateDTO toCreateDTO(CocModuleDetailVO detail) {
        CocModule module = detail.getModule();
        CocModuleCreateDTO result = new CocModuleCreateDTO();
        result.setName(module.getName());
        result.setAuthor(module.getAuthor());
        result.setEra(module.getEra());
        result.setIntroduction(module.getIntroduction());
        result.setInvestigatorCreation(module.getInvestigatorCreation());
        result.setCoverUrl(module.getCoverUrl());
        result.setPlayerCount(module.getPlayerCount());
        result.setEstimatedDuration(module.getEstimatedDuration());
        result.setVisible(module.getVisible());
        if (detail.getContext() != null) {
            CocModuleContext source = detail.getContext();
            CocModuleCreateDTO.GlobalContext context =
                    new CocModuleCreateDTO.GlobalContext();
            context.setTruthBackground(source.getTruthBackground());
            context.setInvestigatorIntro(source.getInvestigatorIntro());
            context.setTimeline(source.getTimeline());
            context.setSpecialRules(source.getSpecialRules());
            context.setKeeperGuidance(source.getKeeperGuidance());
            context.setEndingContent(source.getEndingContent());
            context.setExtraContent(source.getExtraContent());
            result.setContext(context);
        }
        result.setLocations(detail.getLocations().stream().map(source -> {
            CocModuleCreateDTO.Location location =
                    new CocModuleCreateDTO.Location();
            location.setName(source.getName());
            location.setSummary(source.getSummary());
            location.setContent(source.getContent());
            return location;
        }).toList());
        result.setClues(detail.getClues().stream().map(source -> {
            CocModuleCreateDTO.Clue clue = new CocModuleCreateDTO.Clue();
            clue.setTitle(source.getTitle());
            clue.setContent(source.getContent());
            clue.setImportant(source.getImportant());
            return clue;
        }).toList());
        result.setMaterials(detail.getMaterials().stream().map(source -> {
            CocModuleCreateDTO.Material material =
                    new CocModuleCreateDTO.Material();
            material.setTitle(source.getTitle());
            material.setDescription(source.getDescription());
            material.setImageUrl(source.getImageUrl());
            return material;
        }).toList());
        result.setCharacters(detail.getCharacters().stream()
                .map(CocModuleCharacter::getCardData).toList());
        return result;
    }

    private void validateCreate(CocModuleCreateDTO request) {
        if (request == null || !StringUtils.hasText(request.getName())) {
            throw new UserRequestException("模组名称不能为空");
        }
        if (!StringUtils.hasText(request.getIntroduction())) {
            throw new UserRequestException("模组简介不能为空");
        }
        Set<String> locationNames = new HashSet<>();
        for (CocModuleCreateDTO.Location location : safe(request.getLocations())) {
            if (location == null || !StringUtils.hasText(location.getName())
                    || !StringUtils.hasText(location.getSummary())
                    || !StringUtils.hasText(location.getContent())) {
                throw new UserRequestException("地点名称、摘要和原文不能为空");
            }
            if (!locationNames.add(location.getName().trim())) {
                throw new UserRequestException("同一模组内地点名称不能重复");
            }
        }
        Set<String> clueTitles = new HashSet<>();
        for (CocModuleCreateDTO.Clue clue : safe(request.getClues())) {
            if (clue == null || !StringUtils.hasText(clue.getTitle())
                    || !StringUtils.hasText(clue.getContent())) {
                throw new UserRequestException("线索标题和原文不能为空");
            }
            if (!clueTitles.add(clue.getTitle().trim())) {
                throw new UserRequestException("同一模组内线索标题不能重复");
            }
        }
        Set<String> materialTitles = new HashSet<>();
        for (CocModuleCreateDTO.Material material : safe(request.getMaterials())) {
            if (material == null || !StringUtils.hasText(material.getTitle())
                    || !StringUtils.hasText(material.getDescription())) {
                throw new UserRequestException("材料标题和介绍不能为空");
            }
            if (!materialTitles.add(material.getTitle().trim())) {
                throw new UserRequestException("同一模组内材料标题不能重复");
            }
        }
        validateCharacterDamageBonuses(safe(request.getCharacters()));
    }

    private void validateCharacterDamageBonuses(List<JsonNode> characters) {
        for (JsonNode card : characters) {
            if (card == null || card.isNull()) {
                continue;
            }
            JsonNode character = card.get("character");
            if (character == null || character.isNull()) {
                continue;
            }
            JsonNode damageBonusNode = character.get("damageBonus");
            if (damageBonusNode == null || damageBonusNode.isNull()
                    || damageBonusNode.asText().isBlank()) {
                continue;
            }
            String damageBonus = damageBonusNode.asText().trim()
                    .toUpperCase(Locale.ROOT);
            if (DAMAGE_BONUS.matcher(damageBonus).matches()) {
                continue;
            }
            JsonNode nameNode = character.get("name");
            String name = nameNode == null || nameNode.asText().isBlank()
                    ? "未命名角色" : nameNode.asText().trim();
            throw new UserRequestException(
                    "预设角色“" + name + "”的伤害加值（DB）格式不合法");
        }
    }

    private void insertContext(Long moduleId, CocModuleCreateDTO.GlobalContext source,
                               LocalDateTime now) {
        if (source == null) {
            return;
        }
        contextMapper.insert(new CocModuleContext()
                .setModuleId(moduleId)
                .setTruthBackground(trimToNull(source.getTruthBackground()))
                .setInvestigatorIntro(trimToNull(source.getInvestigatorIntro()))
                .setTimeline(trimToNull(source.getTimeline()))
                .setSpecialRules(trimToNull(source.getSpecialRules()))
                .setKeeperGuidance(trimToNull(source.getKeeperGuidance()))
                .setEndingContent(trimToNull(source.getEndingContent()))
                .setExtraContent(trimToNull(source.getExtraContent()))
                .setCreatedAt(now)
                .setUpdatedAt(now));
    }

    private void insertLocations(Long moduleId, List<CocModuleCreateDTO.Location> locations,
                                 LocalDateTime now) {
        for (CocModuleCreateDTO.Location source : locations) {
            locationMapper.insert(new CocModuleLocation()
                    .setModuleId(moduleId)
                    .setName(source.getName().trim())
                    .setSummary(source.getSummary().trim())
                    .setContent(source.getContent().trim())
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
    }

    private void insertClues(Long moduleId, List<CocModuleCreateDTO.Clue> clues,
                             LocalDateTime now) {
        for (CocModuleCreateDTO.Clue source : clues) {
            clueMapper.insert(new CocModuleClue()
                    .setModuleId(moduleId)
                    .setTitle(source.getTitle().trim())
                    .setContent(source.getContent().trim())
                    .setImportant(Boolean.TRUE.equals(source.getImportant()))
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
    }

    private void insertMaterials(Long moduleId, List<CocModuleCreateDTO.Material> materials,
                                 LocalDateTime now) {
        for (CocModuleCreateDTO.Material source : materials) {
            materialMapper.insert(new CocModuleMaterial()
                    .setModuleId(moduleId)
                    .setTitle(source.getTitle().trim())
                    .setDescription(source.getDescription().trim())
                    .setImageUrl(source.getImageUrl() == null
                            ? "" : source.getImageUrl().trim())
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
    }

    private void insertCharacters(
            Long moduleId,
            List<JsonNode> characters,
            LocalDateTime now) {
        for (int index = 0; index < characters.size(); index++) {
            moduleCharacterMapper.insert(new CocModuleCharacter()
                    .setModuleId(moduleId)
                    .setSortOrder(index)
                    .setCardData(characters.get(index))
                    .setCreatedAt(now)
                    .setUpdatedAt(now));
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
