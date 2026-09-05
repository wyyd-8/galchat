package com.me.galchat.service.impl.group;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupActorRuntimeSaveDTO;
import com.me.galchat.domain.po.GroupActorRuntimeConfig;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserModelApi;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.vo.GroupActorRuntimeVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupChatClientFactory;
import com.me.galchat.mapper.GroupActorRuntimeConfigMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.UserModelApiMapper;
import com.me.galchat.modelapi.ResolvedUserModelRuntime;
import com.me.galchat.modelapi.UserModelRuntimeProvider;
import com.me.galchat.service.IUserWorldPrefixService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GroupActorRuntimeService {

    private final GroupConversationService conversationService;
    private final GroupActorRuntimeConfigMapper configMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final UserModelApiMapper modelApiMapper;
    private final UserModelRuntimeProvider runtimeProvider;
    private final GroupChatClientFactory clientFactory;
    private final IUserWorldPrefixService userWorldPrefixService;

    public GroupActorRuntimeService(
            GroupConversationService conversationService,
            GroupActorRuntimeConfigMapper configMapper,
            GroupChatReplyStepMapper stepMapper,
            UserModelApiMapper modelApiMapper,
            UserModelRuntimeProvider runtimeProvider,
            GroupChatClientFactory clientFactory,
            IUserWorldPrefixService userWorldPrefixService) {
        this.conversationService = conversationService;
        this.configMapper = configMapper;
        this.stepMapper = stepMapper;
        this.modelApiMapper = modelApiMapper;
        this.runtimeProvider = runtimeProvider;
        this.clientFactory = clientFactory;
        this.userWorldPrefixService = userWorldPrefixService;
    }

    public List<GroupActorRuntimeVO> list(Long conversationId) {
        GroupConversation conversation =
                conversationService.requireAuthorized(conversationId);
        Long userId = requireUserId(conversation);
        Map<String, GroupActorRuntimeConfig> saved = new LinkedHashMap<>();
        for (GroupActorRuntimeConfig config
                : configMapper.selectByConversationId(conversationId)) {
            saved.put(config.getActorKey(), config);
        }
        Map<Long, UserModelApi> models = new LinkedHashMap<>();
        for (UserModelApi model : modelApiMapper.selectByUserId(userId)) {
            models.put(model.getId(), model);
        }
        List<GroupActorRuntimeVO> result = new ArrayList<>();
        for (GroupChatMember member
                : conversationService.listMembers(conversationId)) {
            if (GroupChatConstant.ACTOR_CHARACTER.equals(
                    member.getActorType())) {
                result.add(toVO(saved.get(actorKey(
                        member.getActorType(), member.getActorId())),
                        member.getActorType(), member.getActorId(), models));
            }
        }
        if (GroupChatConstant.MODE_TRPG.equals(conversation.getMode())) {
            result.add(toVO(saved.get(actorKey(
                            GroupChatConstant.ACTOR_KP, null)),
                    GroupChatConstant.ACTOR_KP, null, models));
        }
        return List.copyOf(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public GroupActorRuntimeVO save(
            Long conversationId, GroupActorRuntimeSaveDTO dto) {
        if (dto == null) {
            throw new UserRequestException("角色运行配置不能为空");
        }
        GroupConversation conversation =
                conversationService.requireAuthorized(conversationId);
        String actorType = dto.getActorType();
        Long actorId = dto.getActorId();
        conversationService.checkReplyMember(
                conversationId, actorType, actorId, false);
        String controlMode = normalizeControlMode(dto.getControlMode());
        if (GroupChatConstant.ACTOR_KP.equals(actorType)
                && GroupChatConstant.CONTROL_MANUAL.equals(controlMode)) {
            throw new UserRequestException("KP不支持人工接管");
        }
        Long userId = requireUserId(conversation);
        UserModelApi model = null;
        if (dto.getModelApiId() != null) {
            model = modelApiMapper.selectOwned(dto.getModelApiId(), userId);
            if (model == null) {
                throw new UserRequestException(
                        "模型 API 配置不存在或无权操作");
            }
        }
        String actorKey = actorKey(actorType, actorId);
        GroupActorRuntimeConfig config = configMapper.selectByActorKey(
                conversationId, actorKey);
        LocalDateTime now = LocalDateTime.now();
        if (config == null) {
            config = new GroupActorRuntimeConfig()
                    .setConversationId(conversationId)
                    .setActorKey(actorKey)
                    .setActorType(actorType)
                    .setActorId(actorId)
                    .setCreatedAt(now);
        }
        config.setControlMode(controlMode)
                .setModelApiId(dto.getModelApiId())
                .setUpdatedAt(now);
        if (config.getId() == null) {
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }
        return new GroupActorRuntimeVO(
                actorType, actorId, controlMode, dto.getModelApiId(),
                model == null ? null : model.getName(), true);
    }

    public StepRuntime snapshot(
            GroupConversation conversation, GroupChatReplyStep step) {
        if (step.getExecutionMode() != null) {
            return new StepRuntime(
                    step.getExecutionMode(), step.getModelApiId());
        }
        GroupActorRuntimeConfig config = configMapper.selectByActorKey(
                conversation.getId(), actorKey(
                        step.getSpeakerType(), step.getSpeakerId()));
        String controlMode = config == null
                ? GroupChatConstant.CONTROL_MODEL
                : normalizeControlMode(config.getControlMode());
        Long modelApiId = config == null ? null : config.getModelApiId();
        step.setExecutionMode(controlMode).setModelApiId(modelApiId);
        if (step.getId() != null) {
            stepMapper.updateById(step);
        }
        return new StepRuntime(controlMode, modelApiId);
    }

    public ChatClient chatClient(
            GroupConversation conversation,
            GroupChatReplyStep step,
            ChatClient fallback) {
        StepRuntime runtime = snapshot(conversation, step);
        if (runtime.modelApiId() == null) {
            return fallback;
        }
        Long userId = requireUserId(conversation);
        return runtimeProvider.resolveIfPresent(userId, runtime.modelApiId())
                .map(ResolvedUserModelRuntime::newChatClientBuilder)
                .map(clientFactory::create)
                .orElse(fallback);
    }

    private GroupActorRuntimeVO toVO(
            GroupActorRuntimeConfig config,
            String actorType,
            Long actorId,
            Map<Long, UserModelApi> models) {
        String controlMode = config == null
                ? GroupChatConstant.CONTROL_MODEL
                : normalizeControlMode(config.getControlMode());
        Long modelApiId = config == null ? null : config.getModelApiId();
        UserModelApi model = modelApiId == null
                ? null : models.get(modelApiId);
        return new GroupActorRuntimeVO(
                actorType, actorId, controlMode, modelApiId,
                model == null ? null : model.getName(),
                modelApiId == null || model != null);
    }

    private String normalizeControlMode(String controlMode) {
        String value = controlMode == null
                ? GroupChatConstant.CONTROL_MODEL
                : controlMode.trim().toUpperCase();
        if (!GroupChatConstant.CONTROL_MODEL.equals(value)
                && !GroupChatConstant.CONTROL_MANUAL.equals(value)) {
            throw new UserRequestException("控制方式仅支持MODEL或MANUAL");
        }
        return value;
    }

    private String actorKey(String actorType, Long actorId) {
        if (GroupChatConstant.ACTOR_KP.equals(actorType)) {
            if (actorId != null) {
                throw new UserRequestException("KP的actorId必须为空");
            }
            return GroupChatConstant.ACTOR_KP;
        }
        if (!GroupChatConstant.ACTOR_CHARACTER.equals(actorType)
                || actorId == null) {
            throw new UserRequestException("仅支持角色或KP运行配置");
        }
        return GroupChatConstant.ACTOR_CHARACTER + ":" + actorId;
    }

    private Long requireUserId(GroupConversation conversation) {
        UserWorldPrefix userWorld = userWorldPrefixService.getById(
                conversation.getUserWorldId());
        if (userWorld == null || userWorld.getUserId() == null) {
            throw new UserRequestException("群聊所属用户不存在");
        }
        return userWorld.getUserId();
    }

    public record StepRuntime(String controlMode, Long modelApiId) {
        public boolean manual() {
            return GroupChatConstant.CONTROL_MANUAL.equals(controlMode);
        }
    }
}
