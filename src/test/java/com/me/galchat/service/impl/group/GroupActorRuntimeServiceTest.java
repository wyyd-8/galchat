package com.me.galchat.service.impl.group;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupActorRuntimeSaveDTO;
import com.me.galchat.domain.po.GroupActorRuntimeConfig;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserModelApi;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupChatClientFactory;
import com.me.galchat.mapper.GroupActorRuntimeConfigMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.UserModelApiMapper;
import com.me.galchat.modelapi.ResolvedUserModelRuntime;
import com.me.galchat.modelapi.UserModelRuntimeProvider;
import com.me.galchat.service.IUserWorldPrefixService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupActorRuntimeServiceTest {

    private GroupConversationService conversationService;
    private GroupActorRuntimeConfigMapper configMapper;
    private GroupChatReplyStepMapper stepMapper;
    private UserModelApiMapper modelApiMapper;
    private UserModelRuntimeProvider runtimeProvider;
    private GroupChatClientFactory clientFactory;
    private GroupActorRuntimeService service;

    @BeforeEach
    void setUp() {
        conversationService = mock(GroupConversationService.class);
        configMapper = mock(GroupActorRuntimeConfigMapper.class);
        stepMapper = mock(GroupChatReplyStepMapper.class);
        modelApiMapper = mock(UserModelApiMapper.class);
        runtimeProvider = mock(UserModelRuntimeProvider.class);
        clientFactory = mock(GroupChatClientFactory.class);
        IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
        when(worlds.getById(5L)).thenReturn(
                new UserWorldPrefix().setId(5L).setUserId(7L));
        service = new GroupActorRuntimeService(
                conversationService, configMapper, stepMapper,
                modelApiMapper, runtimeProvider, clientFactory, worlds);
    }

    @Test
    void savesManualControlForAConversationCharacterWithoutCapabilityChecks() {
        GroupConversation conversation = conversation("chat");
        when(conversationService.requireAuthorized(9L))
                .thenReturn(conversation);
        when(configMapper.selectByActorKey(9L, "character:12"))
                .thenReturn(null);
        when(configMapper.insert(any(GroupActorRuntimeConfig.class)))
                .thenAnswer(invocation -> {
                    invocation.<GroupActorRuntimeConfig>getArgument(0)
                            .setId(31L);
                    return 1;
                });

        var saved = service.save(9L, new GroupActorRuntimeSaveDTO()
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(12L)
                .setControlMode(GroupChatConstant.CONTROL_MANUAL)
                .setModelApiId(null));

        assertThat(saved.controlMode())
                .isEqualTo(GroupChatConstant.CONTROL_MANUAL);
        verify(conversationService).checkReplyMember(
                9L, GroupChatConstant.ACTOR_CHARACTER, 12L, false);
        verify(modelApiMapper, never()).selectOwned(any(), any());
    }

    @Test
    void rejectsManualControlForKp() {
        when(conversationService.requireAuthorized(9L))
                .thenReturn(conversation("trpg"));

        assertThatThrownBy(() -> service.save(
                9L, new GroupActorRuntimeSaveDTO()
                        .setActorType(GroupChatConstant.ACTOR_KP)
                        .setControlMode(GroupChatConstant.CONTROL_MANUAL)))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("KP");
        verify(configMapper, never()).insert(
                any(GroupActorRuntimeConfig.class));
    }

    @Test
    void validatesOnlyModelOwnershipWhenBinding() {
        UserModelApi failedModel = new UserModelApi()
                .setId(44L).setUserId(7L).setName("自选模型")
                .setStatus("FAILED");
        when(conversationService.requireAuthorized(9L))
                .thenReturn(conversation("chat"));
        when(modelApiMapper.selectOwned(44L, 7L))
                .thenReturn(failedModel);
        when(configMapper.insert(any(GroupActorRuntimeConfig.class)))
                .thenReturn(1);

        var saved = service.save(9L, new GroupActorRuntimeSaveDTO()
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(12L)
                .setControlMode(GroupChatConstant.CONTROL_MODEL)
                .setModelApiId(44L));

        assertThat(saved.modelApiId()).isEqualTo(44L);
        assertThat(saved.modelApiAvailable()).isTrue();
    }

    @Test
    void snapshotsTheActorConfigurationWhenTheStepFirstExecutes() {
        GroupConversation conversation = conversation("trpg");
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setId(80L)
                .setSpeakerType(GroupChatConstant.ACTOR_CHARACTER)
                .setSpeakerId(12L);
        when(configMapper.selectByActorKey(9L, "character:12"))
                .thenReturn(new GroupActorRuntimeConfig()
                        .setControlMode(GroupChatConstant.CONTROL_MANUAL)
                        .setModelApiId(44L));

        var assignment = service.snapshot(conversation, step);

        assertThat(assignment.controlMode())
                .isEqualTo(GroupChatConstant.CONTROL_MANUAL);
        assertThat(assignment.modelApiId()).isEqualTo(44L);
        assertThat(step.getExecutionMode())
                .isEqualTo(GroupChatConstant.CONTROL_MANUAL);
        verify(stepMapper).updateById(step);
    }

    @Test
    void usesDefaultClientOnlyWhenTheBoundModelNoLongerExists() {
        GroupConversation conversation = conversation("chat");
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setExecutionMode(GroupChatConstant.CONTROL_MODEL)
                .setModelApiId(44L);
        ChatClient fallback = mock(ChatClient.class);
        when(runtimeProvider.resolveIfPresent(7L, 44L))
                .thenReturn(Optional.empty());

        assertThat(service.chatClient(conversation, step, fallback))
                .isSameAs(fallback);
        verify(clientFactory, never()).create(any());
    }

    @Test
    void doesNotFallBackWhenAnExistingBoundModelCannotBeResolved() {
        GroupConversation conversation = conversation("chat");
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setExecutionMode(GroupChatConstant.CONTROL_MODEL)
                .setModelApiId(44L);
        ChatClient fallback = mock(ChatClient.class);
        when(runtimeProvider.resolveIfPresent(7L, 44L))
                .thenThrow(new UserRequestException(
                        "模型 API 地址解析失败"));

        assertThatThrownBy(() -> service.chatClient(
                conversation, step, fallback))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("地址解析失败");
        verify(clientFactory, never()).create(any());
    }

    @Test
    void buildsAGroupClientForAnExistingBoundModel() {
        GroupConversation conversation = conversation("chat");
        GroupChatReplyStep step = new GroupChatReplyStep()
                .setExecutionMode(GroupChatConstant.CONTROL_MODEL)
                .setModelApiId(44L);
        ChatClient fallback = mock(ChatClient.class);
        ChatClient selected = mock(ChatClient.class);
        ChatModel model = mock(ChatModel.class);
        ResolvedUserModelRuntime runtime = new ResolvedUserModelRuntime(
                7L, 44L, new UserModelApi(), model);
        when(runtimeProvider.resolveIfPresent(7L, 44L))
                .thenReturn(Optional.of(runtime));
        when(clientFactory.create(any(ChatClient.Builder.class)))
                .thenReturn(selected);

        assertThat(service.chatClient(conversation, step, fallback))
                .isSameAs(selected);
    }

    @Test
    void listsDefaultEntriesForCharactersAndKp() {
        GroupConversation conversation = conversation("trpg");
        when(conversationService.requireAuthorized(9L))
                .thenReturn(conversation);
        when(conversationService.listMembers(9L)).thenReturn(List.of(
                new GroupChatMember()
                        .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                        .setActorId(12L)));
        when(configMapper.selectByConversationId(9L)).thenReturn(List.of());
        when(modelApiMapper.selectByUserId(7L)).thenReturn(List.of());

        var values = service.list(9L);

        assertThat(values).extracting(value -> value.actorType())
                .containsExactly(
                        GroupChatConstant.ACTOR_CHARACTER,
                        GroupChatConstant.ACTOR_KP);
        assertThat(values).allSatisfy(value -> {
            assertThat(value.controlMode())
                    .isEqualTo(GroupChatConstant.CONTROL_MODEL);
            assertThat(value.modelApiId()).isNull();
        });
    }

    private GroupConversation conversation(String mode) {
        return new GroupConversation()
                .setId(9L).setUserWorldId(5L).setMode(mode);
    }
}
