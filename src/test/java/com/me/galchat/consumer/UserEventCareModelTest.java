package com.me.galchat.consumer;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.me.galchat.domain.dto.UserEventLogDelayTaskDTO;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.modelapi.*;
import com.me.galchat.service.*;
import com.me.galchat.service.impl.chat.*;
import com.me.galchat.singlechat.SingleChatClientFactory;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UserEventCareModelTest {
    @ParameterizedTest
    @CsvSource({"upcoming,true", "daily_care,true", "daily_care,false"})
    void allCareKindsUseTheCharactersModelWithoutSingleChatMemoryOrTools(String kind, boolean hasEvents) {
        MybatisPlusTestSupport.initialize(UserCharacterInfo.class, UserEventLog.class);
        var worlds = mock(IUserWorldPrefixService.class);
        var characters = mock(IUserCharacterInfoService.class);
        var characterMapper = mock(UserCharacterInfoMapper.class);
        var events = mock(IUserEventLogService.class);
        var eventMapper = mock(UserEventLogMapper.class);
        var histories = mock(UserChatHistoryMapper.class);
        var locks = mock(SingleChatLockService.class);
        var provider = mock(UserModelRuntimeProvider.class);
        var singleChatFactory = mock(SingleChatClientFactory.class);
        var runtime = new SingleChatRuntimeService(characterMapper, mock(UserModelApiMapper.class),
                provider, singleChatFactory, worlds);
        var selectedModel = mock(ChatModel.class);
        when(selectedModel.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().model("role-model").build());
        var fallback = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(fallback.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(hasEvents ? "默认模型" : "{\"topic\":\"默认\",\"message\":\"默认模型\"}");
        var character = new UserCharacterInfo().setUserWorldId(10L).setCharacterId(20L).setModelApiId(44L);
        when(worlds.getById(10L)).thenReturn(new UserWorldPrefix().setId(10L).setUserId(7L).setAcitvePushStatus(true));
        when(characters.getOne(any())).thenReturn(character);
        when(characterMapper.selectOne(any())).thenReturn(character);
        when(locks.tryLock(10L,20L)).thenReturn(mock(RLock.class));
        when(events.listByIds(List.of(30L))).thenReturn(List.of(new UserEventLog()
                .setId(30L).setUserWorldId(10L).setCharacterId(20L).setEventDescription("考试")));
        when(events.lambdaQuery()).thenAnswer(i -> new LambdaQueryChainWrapper<>(eventMapper));
        when(eventMapper.selectList(any())).thenReturn(List.of());
        when(provider.resolveIfPresent(7L,44L)).thenReturn(Optional.of(
                new ResolvedUserModelRuntime(7L,44L,new UserModelApi(),selectedModel)));
        when(selectedModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage(hasEvents ? "角色模型" : "{\"topic\":\"勇气\",\"message\":\"角色模型\"}")))));
        var consumer = new UserEventLogConsumer(mock(RedissonClient.class), events, worlds, histories,
                characters, mock(TopicBoundaryService.class), locks, runtime);
        ReflectionTestUtils.setField(consumer,"userEventCareClient",fallback);
        var task = new UserEventLogDelayTaskDTO().setUserWorldId(10L).setCharacterId(20L)
                .setTaskType(kind).setUserEventLogIds(hasEvents ? List.of(30L) : List.of());

        ReflectionTestUtils.invokeMethod(consumer,"handleUserEventLogTask",task);

        verify(histories).insert(argThat((UserChatHistory message) -> "角色模型".equals(message.getContent())));
        verify(provider).resolveIfPresent(7L,44L);
        verifyNoInteractions(singleChatFactory);
    }
}
