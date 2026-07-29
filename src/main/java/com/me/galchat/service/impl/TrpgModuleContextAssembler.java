package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocModuleClue;
import com.me.galchat.domain.po.CocModuleContext;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.CocModuleMaterial;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocModuleClueMapper;
import com.me.galchat.mapper.CocModuleContextMapper;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.CocModuleMaterialMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TrpgModuleContextAssembler {

    private final CocModuleMapper moduleMapper;
    private final CocModuleContextMapper contextMapper;
    private final CocModuleLocationMapper locationMapper;
    private final CocModuleClueMapper clueMapper;
    private final CocModuleMaterialMapper materialMapper;
    private final GroupReplyPlanMapper planMapper;
    private final CocCharacterMapper characterMapper;
    private final TrpgMaterialStateStore materialStateStore;

    public String formatKpContext(GroupConversation conversation) {
        if (conversation == null || conversation.getModuleId() == null) {
            throw new UserRequestException("TRPG群聊未绑定模组");
        }
        Long moduleId = conversation.getModuleId();
        CocModule module = moduleMapper.selectById(moduleId);
        if (module == null) {
            throw new UserRequestException("模组不存在");
        }
        CocModuleContext global = contextMapper.selectOne(
                new LambdaQueryWrapper<CocModuleContext>()
                        .eq(CocModuleContext::getModuleId, moduleId)
                        .last("limit 1"));
        List<CocModuleLocation> locations = locationMapper.selectList(
                new LambdaQueryWrapper<CocModuleLocation>()
                        .eq(CocModuleLocation::getModuleId, moduleId)
                        .orderByAsc(CocModuleLocation::getId));
        List<CocModuleClue> clues = clueMapper.selectList(
                new LambdaQueryWrapper<CocModuleClue>()
                        .eq(CocModuleClue::getModuleId, moduleId)
                        .orderByAsc(CocModuleClue::getId));
        List<CocModuleMaterial> materials = materialMapper.selectList(
                new LambdaQueryWrapper<CocModuleMaterial>()
                        .eq(CocModuleMaterial::getModuleId, moduleId)
                        .orderByAsc(CocModuleMaterial::getId));
        Set<Long> shownIds = materialStateStore.shownIds(
                conversation.getId());
        Long currentLocationId = currentLocationId(conversation);

        StringBuilder result = new StringBuilder();
        result.append("<module>\n");
        appendLine(result, "名称", module.getName());
        appendLine(result, "作者", module.getAuthor());
        appendLine(result, "时代", module.getEra());
        appendLine(result, "简介", module.getIntroduction());
        appendLine(result, "调查员创建", module.getInvestigatorCreation());
        result.append("</module>\n");
        appendGlobal(result, global);

        result.append("<location-title-index>\n");
        for (CocModuleLocation location : locations) {
            result.append("- ").append(location.getName())
                    .append("：").append(location.getSummary()).append('\n');
        }
        result.append("</location-title-index>\n");

        result.append("<clue-title-index>\n");
        for (CocModuleClue clue : clues) {
            result.append("- ").append(clue.getTitle());
            if (Boolean.TRUE.equals(clue.getImportant())) {
                result.append("（重要）：").append(clue.getContent());
            }
            result.append('\n');
        }
        result.append("</clue-title-index>\n");

        if (currentLocationId != null) {
            locations.stream()
                    .filter(location -> currentLocationId.equals(location.getId()))
                    .findFirst()
                    .ifPresent(location -> result
                            .append("<current-location name=\"")
                            .append(escape(location.getName())).append("\">\n")
                            .append(location.getContent()).append('\n')
                            .append("</current-location>\n"));
        }

        result.append("<material-title-index>\n");
        for (CocModuleMaterial material : materials) {
            result.append("- ").append(material.getTitle())
                    .append("｜")
                    .append(shownIds.contains(material.getId())
                            ? "已展示" : "未展示")
                    .append("：").append(material.getDescription())
                    .append('\n');
        }
        result.append("</material-title-index>\n");
        appendQuickNotes(result, conversation.getUserWorldId());
        return result.toString();
    }

    private Long currentLocationId(GroupConversation conversation) {
        if (conversation.getActiveReplyPlanId() == null) {
            return null;
        }
        GroupReplyPlan active = planMapper.selectById(
                conversation.getActiveReplyPlanId());
        if (active == null) {
            return null;
        }
        if (GroupChatConstant.PLAN_SOURCE_SCENE.equals(active.getSource())) {
            return active.getContextId();
        }
        if (GroupChatConstant.PLAN_SOURCE_COMBAT.equals(active.getSource())
                && active.getResumePlanId() != null) {
            GroupReplyPlan resume = planMapper.selectById(
                    active.getResumePlanId());
            return resume == null ? null : resume.getContextId();
        }
        return null;
    }

    private void appendGlobal(
            StringBuilder result, CocModuleContext global) {
        if (global == null) {
            return;
        }
        result.append("<module-global-context>\n");
        appendElement(result, "truth-background",
                global.getTruthBackground());
        appendElement(result, "investigator-intro",
                global.getInvestigatorIntro());
        appendElement(result, "timeline", global.getTimeline());
        appendElement(result, "special-rules", global.getSpecialRules());
        appendElement(result, "keeper-guidance",
                global.getKeeperGuidance());
        appendElement(result, "ending-content",
                global.getEndingContent());
        appendElement(result, "extra-content", global.getExtraContent());
        result.append("</module-global-context>\n");
    }

    private void appendQuickNotes(StringBuilder result, Long runId) {
        List<CocCharacter> characters = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId)
                        .orderByAsc(CocCharacter::getId));
        boolean opened = false;
        for (CocCharacter character : characters) {
            if (!StringUtils.hasText(character.getQuickNotes())) {
                continue;
            }
            if (!opened) {
                result.append("<kp-quick-notes>\n");
                opened = true;
            }
            result.append("- ").append(character.getName())
                    .append("：").append(character.getQuickNotes().trim())
                    .append('\n');
        }
        if (opened) {
            result.append("</kp-quick-notes>\n");
        }
    }

    private void appendLine(
            StringBuilder result, String label, String value) {
        if (StringUtils.hasText(value)) {
            result.append(label).append('：')
                    .append(value.trim()).append('\n');
        }
    }

    private void appendElement(
            StringBuilder result, String name, String value) {
        if (StringUtils.hasText(value)) {
            result.append('<').append(name).append('>')
                    .append(value.trim())
                    .append("</").append(name).append(">\n");
        }
    }

    private String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
