package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserModelApi;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserModelApiMapper;
import com.me.galchat.modelapi.ResolvedUserModelRuntime;
import com.me.galchat.modelapi.UserModelRuntimeProvider;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.singlechat.SingleChatClientFactory;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SingleChatRuntimeServiceTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                UserCharacterInfo.class);
    }

    private UserCharacterInfoMapper characterMapper;
    private UserModelApiMapper modelApiMapper;
    private UserModelRuntimeProvider runtimeProvider;
    private SingleChatClientFactory clientFactory;
    private IUserWorldPrefixService worldService;
    private SingleChatRuntimeService service;

    @BeforeEach
    void setUp() {
        characterMapper = mock(UserCharacterInfoMapper.class);
        modelApiMapper = mock(UserModelApiMapper.class);
        runtimeProvider = mock(UserModelRuntimeProvider.class);
        clientFactory = mock(SingleChatClientFactory.class);
        worldService = mock(IUserWorldPrefixService.class);
        service = new SingleChatRuntimeService(characterMapper, modelApiMapper,
                runtimeProvider, clientFactory, worldService);
    }

    @Test
    void savesAnOwnedModelForAnOwnedCharacter() {
        when(worldService.checkUserWorldAuth(7L, 5L, true))
                .thenReturn(new UserWorldPrefix().setId(5L).setUserId(7L));
        when(characterMapper.selectOne(any())).thenReturn(new UserCharacterInfo()
                .setUserWorldId(5L).setCharacterId(12L));
        when(modelApiMapper.selectOwned(44L, 7L)).thenReturn(new UserModelApi()
                .setId(44L).setName("自选模型"));
        when(characterMapper.update(any(UserCharacterInfo.class), any())).thenReturn(1);

        var saved = service.saveModel(7L, 5L, 12L, 44L);

        assertThat(saved.modelApiId()).isEqualTo(44L);
        assertThat(saved.modelApiName()).isEqualTo("自选模型");
        assertThat(saved.modelApiAvailable()).isTrue();
    }

    @Test
    void rejectsBindingAnotherUsersModel() {
        when(worldService.checkUserWorldAuth(7L, 5L, true))
                .thenReturn(new UserWorldPrefix().setId(5L).setUserId(7L));
        when(characterMapper.selectOne(any())).thenReturn(new UserCharacterInfo()
                .setUserWorldId(5L).setCharacterId(12L));
        when(modelApiMapper.selectOwned(44L, 7L)).thenReturn(null);

        assertThatThrownBy(() -> service.saveModel(7L, 5L, 12L, 44L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("不存在或无权操作");
        verify(characterMapper, never()).update(any(UserCharacterInfo.class), any());
    }

    @Test
    void usesDefaultClientWhenNoModelIsBound() {
        when(characterMapper.selectOne(any())).thenReturn(new UserCharacterInfo()
                .setModelApiId(null));
        ChatClient fallback = mock(ChatClient.class);

        assertThat(service.chatClient(5L, 12L, fallback)).isSameAs(fallback);
        verify(runtimeProvider, never()).resolveIfPresent(any(), any());
    }

    @Test
    void usesDefaultClientWhenBoundModelWasDeleted() {
        when(characterMapper.selectOne(any())).thenReturn(new UserCharacterInfo()
                .setModelApiId(44L));
        when(worldService.getById(5L)).thenReturn(new UserWorldPrefix().setUserId(7L));
        when(runtimeProvider.resolveIfPresent(7L, 44L)).thenReturn(Optional.empty());
        ChatClient fallback = mock(ChatClient.class);

        assertThat(service.chatClient(5L, 12L, fallback)).isSameAs(fallback);
        verify(clientFactory, never()).create(any());
    }

    @Test
    void buildsAConfiguredClientForAnExistingModel() {
        when(characterMapper.selectOne(any())).thenReturn(new UserCharacterInfo()
                .setModelApiId(44L));
        when(worldService.getById(5L)).thenReturn(new UserWorldPrefix().setUserId(7L));
        ResolvedUserModelRuntime runtime = new ResolvedUserModelRuntime(
                7L, 44L, new UserModelApi(), mock(ChatModel.class));
        when(runtimeProvider.resolveIfPresent(7L, 44L)).thenReturn(Optional.of(runtime));
        ChatClient fallback = mock(ChatClient.class);
        ChatClient selected = mock(ChatClient.class);
        when(clientFactory.create(any(ChatClient.Builder.class))).thenReturn(selected);

        assertThat(service.chatClient(5L, 12L, fallback)).isSameAs(selected);
    }

    @Test
    void doesNotFallBackWhenExistingModelResolutionFails() {
        when(characterMapper.selectOne(any())).thenReturn(new UserCharacterInfo()
                .setModelApiId(44L));
        when(worldService.getById(5L)).thenReturn(new UserWorldPrefix().setUserId(7L));
        when(runtimeProvider.resolveIfPresent(7L, 44L))
                .thenThrow(new UserRequestException("模型 API 地址解析失败"));

        assertThatThrownBy(() -> service.chatClient(5L, 12L, mock(ChatClient.class)))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("地址解析失败");
        verify(clientFactory, never()).create(any());
    }
}
