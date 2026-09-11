package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModuleMaterial;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocModuleMaterialMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class TrpgMaterialService {

    private final GroupConversationService conversationService;
    private final CocModuleMaterialMapper materialMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupChatMessageMapper messageMapper;
    private final TrpgMaterialStateStore stateStore;
    private final ObjectMapper objectMapper;

    public TrpgMaterialService(
            GroupConversationService conversationService,
            CocModuleMaterialMapper materialMapper,
            GroupChatReplyStepMapper stepMapper,
            GroupChatTurnMapper turnMapper,
            GroupChatMessageMapper messageMapper,
            TrpgMaterialStateStore stateStore,
            ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.materialMapper = materialMapper;
        this.stepMapper = stepMapper;
        this.turnMapper = turnMapper;
        this.messageMapper = messageMapper;
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public DisplayResult showMaterial(
            Long conversationId, Long replyStepId, String materialName) {
        if (!StringUtils.hasText(materialName)) {
            throw new UserRequestException("材料名称不能为空");
        }
        GroupConversation conversation =
                conversationService.requireActive(conversationId);
        if (!GroupChatConstant.MODE_TRPG.equals(conversation.getMode())
                || conversation.getModuleId() == null) {
            throw new UserRequestException("只有绑定模组的TRPG群聊可以展示材料");
        }
        GroupChatReplyStep step = stepMapper.selectById(replyStepId);
        if (step == null || !GroupChatConstant.ACTOR_KP.equals(
                step.getSpeakerType())) {
            throw new UserAuthException("只有KP回复步骤可以展示材料");
        }
        GroupChatTurn turn = turnMapper.selectById(step.getTurnId());
        if (turn == null || !Objects.equals(
                turn.getConversationId(), conversationId)) {
            throw new UserRequestException("材料展示步骤不属于当前群聊");
        }
        CocModuleMaterial material = requireByName(
                conversation.getModuleId(), materialName);
        if (stateStore.isShown(conversationId, material.getId())
                || recoverFromMessages(conversationId, material.getId())) {
            return new DisplayResult(false, null);
        }

        LocalDateTime now = LocalDateTime.now();
        GroupChatMessage message = new GroupChatMessage()
                .setConversationId(conversationId)
                .setSceneId(sceneId(turn))
                .setTurnId(turn.getId())
                .setReplyStepId(replyStepId)
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setSpeakerId(null)
                .setMessageKind(GroupChatConstant.MESSAGE_MATERIAL)
                .setVisibility("public")
                .setContent(encode(material))
                .setSequenceNo(conversationService.nextSequence(conversationId))
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messageMapper.insert(message);
        markShownAfterCommit(conversationId, material.getId());
        return new DisplayResult(true, message);
    }

    private CocModuleMaterial requireByName(
            Long moduleId, String materialName) {
        String exactName = materialName.trim();
        List<CocModuleMaterial> matches = materialMapper.selectList(
                new LambdaQueryWrapper<CocModuleMaterial>()
                        .eq(CocModuleMaterial::getModuleId, moduleId)
                        .eq(CocModuleMaterial::getTitle, exactName))
                .stream()
                .filter(material -> exactName.equals(material.getTitle()))
                .toList();
        if (matches.isEmpty()) {
            throw new UserRequestException("材料不存在");
        }
        if (matches.size() > 1) {
            throw new UserRequestException("材料名称不唯一");
        }
        return matches.getFirst();
    }

    private boolean recoverFromMessages(
            Long conversationId, Long materialId) {
        List<GroupChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<GroupChatMessage>()
                        .eq(GroupChatMessage::getConversationId, conversationId)
                        .eq(GroupChatMessage::getMessageKind,
                                GroupChatConstant.MESSAGE_MATERIAL)
                        .eq(GroupChatMessage::getStatus,
                                GroupChatConstant.STATUS_COMPLETED));
        for (GroupChatMessage message : messages) {
            try {
                Map<?, ?> content = objectMapper.readValue(
                        message.getContent(), Map.class);
                Object value = content.get("materialId");
                if (value instanceof Number number
                        && materialId.equals(number.longValue())) {
                    safeMarkShown(conversationId, materialId);
                    return true;
                }
            } catch (JacksonException exception) {
                log.warn("忽略无法解析的历史材料消息, messageId:{}",
                        message.getId());
            }
        }
        return false;
    }

    private String encode(CocModuleMaterial material) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schemaVersion", 1);
        content.put("materialId", material.getId());
        content.put("title", material.getTitle());
        content.put("description", material.getDescription());
        content.put("imageUrl", material.getImageUrl());
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JacksonException exception) {
            throw new IllegalStateException("材料消息序列化失败", exception);
        }
    }

    private Long sceneId(GroupChatTurn turn) {
        return GroupChatConstant.PLAN_SOURCE_SCENE.equals(
                turn.getPlanSource()) ? turn.getPlanContextId() : null;
    }

    private void markShownAfterCommit(
            Long conversationId, Long materialId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeMarkShown(conversationId, materialId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        safeMarkShown(conversationId, materialId);
                    }
                });
    }

    private void safeMarkShown(Long conversationId, Long materialId) {
        try {
            stateStore.markShown(conversationId, materialId);
        } catch (RuntimeException exception) {
            log.warn("材料消息已保存，但Redis展示状态写入失败, conversationId:{}, materialId:{}",
                    conversationId, materialId, exception);
        }
    }

    public record DisplayResult(boolean shown, GroupChatMessage message) {
    }
}
