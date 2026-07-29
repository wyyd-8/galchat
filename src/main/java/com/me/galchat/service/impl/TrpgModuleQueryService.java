package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.domain.po.CocModuleClue;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.CocModuleMaterial;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocModuleClueMapper;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.CocModuleMaterialMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class TrpgModuleQueryService {

    private final GroupConversationService conversationService;
    private final CocModuleLocationMapper locationMapper;
    private final CocModuleClueMapper clueMapper;
    private final CocModuleMaterialMapper materialMapper;
    private final TrpgMaterialStateStore materialStateStore;

    public LocationResult readLocation(
            Long conversationId, String locationName) {
        GroupConversation conversation =
                requireModuleConversation(conversationId);
        CocModuleLocation location = requireExact(
                locationName,
                locationMapper.selectList(
                        new LambdaQueryWrapper<CocModuleLocation>()
                                .eq(CocModuleLocation::getModuleId,
                                        conversation.getModuleId())
                                .eq(CocModuleLocation::getName,
                                        normalize(locationName))),
                CocModuleLocation::getName,
                "地点");
        return new LocationResult(
                location.getName(), location.getSummary(),
                location.getContent());
    }

    public ClueResult readClue(
            Long conversationId, String clueTitle) {
        GroupConversation conversation =
                requireModuleConversation(conversationId);
        CocModuleClue clue = requireExact(
                clueTitle,
                clueMapper.selectList(
                        new LambdaQueryWrapper<CocModuleClue>()
                                .eq(CocModuleClue::getModuleId,
                                        conversation.getModuleId())
                                .eq(CocModuleClue::getTitle,
                                        normalize(clueTitle))),
                CocModuleClue::getTitle,
                "线索");
        return new ClueResult(
                clue.getTitle(), clue.getContent(),
                Boolean.TRUE.equals(clue.getImportant()));
    }

    public MaterialResult readMaterial(
            Long conversationId, String materialTitle) {
        GroupConversation conversation =
                requireModuleConversation(conversationId);
        CocModuleMaterial material = requireExact(
                materialTitle,
                materialMapper.selectList(
                        new LambdaQueryWrapper<CocModuleMaterial>()
                                .eq(CocModuleMaterial::getModuleId,
                                        conversation.getModuleId())
                                .eq(CocModuleMaterial::getTitle,
                                        normalize(materialTitle))),
                CocModuleMaterial::getTitle,
                "材料");
        return new MaterialResult(
                material.getTitle(), material.getDescription(),
                materialStateStore.isShown(
                        conversationId, material.getId()));
    }

    private GroupConversation requireModuleConversation(
            Long conversationId) {
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        if (conversation.getModuleId() == null) {
            throw new UserRequestException("当前群聊未绑定模组");
        }
        return conversation;
    }

    private <T> T requireExact(
            String input,
            List<T> candidates,
            Function<T, String> nameGetter,
            String label) {
        String exactName = normalize(input);
        List<T> matches = candidates.stream()
                .filter(candidate ->
                        exactName.equals(nameGetter.apply(candidate)))
                .toList();
        if (matches.isEmpty()) {
            throw new UserRequestException(label + "不存在");
        }
        if (matches.size() > 1) {
            throw new UserRequestException(label + "名称不唯一");
        }
        return matches.getFirst();
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            throw new UserRequestException("查询名称不能为空");
        }
        return value.trim();
    }

    public record LocationResult(
            String name, String summary, String content) {
    }

    public record ClueResult(
            String title, String content, boolean important) {
    }

    public record MaterialResult(
            String title, String description, boolean shown) {
    }
}
