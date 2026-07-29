package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.dto.CocModuleCreateDTO;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocModuleClue;
import com.me.galchat.domain.po.CocModuleContext;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.CocModuleMaterial;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CocModuleService {

    private final CocModuleMapper moduleMapper;
    private final CocModuleContextMapper contextMapper;
    private final CocModuleLocationMapper locationMapper;
    private final CocModuleClueMapper clueMapper;
    private final CocModuleMaterialMapper materialMapper;
    private final GroupConversationMapper conversationMapper;
    private final CocModuleLockService lockService;

    @Transactional(rollbackFor = Exception.class)
    public CocModule create(CocModuleCreateDTO request) {
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
                .setCreatedAt(now)
                .setUpdatedAt(now);
        moduleMapper.insert(module);
        insertContext(module.getId(), request.getContext(), now);
        insertLocations(module.getId(), safe(request.getLocations()), now);
        insertClues(module.getId(), safe(request.getClues()), now);
        insertMaterials(module.getId(), safe(request.getMaterials()), now);
        return module;
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
        for (CocModuleCreateDTO.Location location : safe(request.getLocations())) {
            String parentName = trimToNull(location.getParentName());
            if (parentName != null && (!locationNames.contains(parentName)
                    || parentName.equals(location.getName().trim()))) {
                throw new UserRequestException("父地点必须是当前模组内的其他地点");
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
                    || !StringUtils.hasText(material.getDescription())
                    || !StringUtils.hasText(material.getImageUrl())) {
                throw new UserRequestException("材料标题、介绍和图片地址不能为空");
            }
            if (!materialTitles.add(material.getTitle().trim())) {
                throw new UserRequestException("同一模组内材料标题不能重复");
            }
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
        Map<String, Long> insertedIds = new HashMap<>();
        List<CocModuleCreateDTO.Location> pending = new ArrayList<>(locations);
        while (!pending.isEmpty()) {
            int before = pending.size();
            for (var iterator = pending.iterator(); iterator.hasNext();) {
                CocModuleCreateDTO.Location source = iterator.next();
                String parentName = trimToNull(source.getParentName());
                if (parentName != null && !insertedIds.containsKey(parentName)) {
                    continue;
                }
                CocModuleLocation location = new CocModuleLocation()
                        .setModuleId(moduleId)
                        .setParentLocationId(parentName == null ? null : insertedIds.get(parentName))
                        .setName(source.getName().trim())
                        .setSummary(source.getSummary().trim())
                        .setContent(source.getContent().trim())
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
                locationMapper.insert(location);
                insertedIds.put(location.getName(), location.getId());
                iterator.remove();
            }
            if (pending.size() == before) {
                throw new UserRequestException("地点父子关系存在循环");
            }
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
                    .setImageUrl(source.getImageUrl().trim())
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
