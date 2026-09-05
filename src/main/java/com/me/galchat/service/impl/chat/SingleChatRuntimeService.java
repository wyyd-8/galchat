package com.me.galchat.service.impl.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserModelApi;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.vo.SingleChatRuntimeVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserModelApiMapper;
import com.me.galchat.modelapi.ResolvedUserModelRuntime;
import com.me.galchat.modelapi.UserModelRuntimeProvider;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.singlechat.SingleChatClientFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class SingleChatRuntimeService {

    private final UserCharacterInfoMapper characterMapper;
    private final UserModelApiMapper modelApiMapper;
    private final UserModelRuntimeProvider runtimeProvider;
    private final SingleChatClientFactory clientFactory;
    private final IUserWorldPrefixService worldService;

    public SingleChatRuntimeService(
            UserCharacterInfoMapper characterMapper,
            UserModelApiMapper modelApiMapper,
            UserModelRuntimeProvider runtimeProvider,
            SingleChatClientFactory clientFactory,
            IUserWorldPrefixService worldService) {
        this.characterMapper = characterMapper;
        this.modelApiMapper = modelApiMapper;
        this.runtimeProvider = runtimeProvider;
        this.clientFactory = clientFactory;
        this.worldService = worldService;
    }

    public SingleChatRuntimeVO saveModel(
            Long userId, Long userWorldId, Long characterId, Long modelApiId) {
        worldService.checkUserWorldAuth(userId, userWorldId, true);
        requireCharacter(userWorldId, characterId);
        UserModelApi model = null;
        if (modelApiId != null) {
            model = modelApiMapper.selectOwned(modelApiId, userId);
            if (model == null) {
                throw new UserRequestException("模型 API 配置不存在或无权操作");
            }
        }
        int updated = characterMapper.update(new UserCharacterInfo(),
                new LambdaUpdateWrapper<UserCharacterInfo>()
                        .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                        .eq(UserCharacterInfo::getCharacterId, characterId)
                        .set(UserCharacterInfo::getModelApiId, modelApiId));
        if (updated == 0) {
            throw new UserRequestException("角色不存在");
        }
        return new SingleChatRuntimeVO(modelApiId,
                model == null ? null : model.getName(), true);
    }

    public ChatClient chatClient(
            Long userWorldId, Long characterId, ChatClient fallback) {
        UserCharacterInfo character = requireCharacter(userWorldId, characterId);
        Long modelApiId = character.getModelApiId();
        if (modelApiId == null) {
            return fallback;
        }
        UserWorldPrefix world = worldService.getById(userWorldId);
        if (world == null || world.getUserId() == null) {
            throw new UserRequestException("单聊所属用户不存在");
        }
        return runtimeProvider.resolveIfPresent(world.getUserId(), modelApiId)
                .map(ResolvedUserModelRuntime::newChatClientBuilder)
                .map(clientFactory::create)
                .orElse(fallback);
    }

    private UserCharacterInfo requireCharacter(Long userWorldId, Long characterId) {
        if (userWorldId == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        if (characterId == null) {
            throw new UserRequestException("角色id不能为空");
        }
        UserCharacterInfo character = characterMapper.selectOne(
                new LambdaQueryWrapper<UserCharacterInfo>()
                        .select(UserCharacterInfo::getUserWorldId,
                                UserCharacterInfo::getCharacterId,
                                UserCharacterInfo::getModelApiId)
                        .eq(UserCharacterInfo::getUserWorldId, userWorldId)
                        .eq(UserCharacterInfo::getCharacterId, characterId));
        if (character == null) {
            throw new UserRequestException("角色不存在");
        }
        return character;
    }
}
