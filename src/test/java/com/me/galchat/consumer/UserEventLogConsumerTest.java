package com.me.galchat.consumer;

import com.me.galchat.domain.dto.UserEventLogDelayTaskDTO;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserEventLog;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserEventLogMapper;
import com.me.galchat.memory.TopicBoundaryService;
import com.me.galchat.service.IUserCharacterInfoService;
import com.me.galchat.service.IUserEventLogService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.me.galchat.support.MybatisPlusTestSupport;
import com.me.galchat.websocket.WebSocketServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class UserEventLogConsumerTest {

    @ParameterizedTest(name = "push={0}, task={1}, hasEvent={2}")
    @CsvSource({
            "false, daily_care, false",
            "true, daily_care, false",
            "false, daily_care, true",
            "true, daily_care, true",
            "false, upcoming, true",
            "true, upcoming, true"
    })
    void activePushPreferenceControlsAllProactiveMessages(boolean enabled, String taskType, boolean hasEvent) {
        MybatisPlusTestSupport.initialize(UserCharacterInfo.class, UserEventLog.class);
        var worlds = mock(IUserWorldPrefixService.class);
        var characters = mock(IUserCharacterInfoService.class);
        var events = mock(IUserEventLogService.class);
        var eventMapper = mock(UserEventLogMapper.class);
        var histories = mock(UserChatHistoryMapper.class);
        var socket = mock(WebSocketServer.class);
        var topics = mock(TopicBoundaryService.class);
        var client = mock(ChatClient.class);
        var request = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        var response = mock(ChatClient.CallResponseSpec.class);
        var consumer = new UserEventLogConsumer(mock(RedissonClient.class), socket, events, worlds,
                histories, characters, topics);
        ReflectionTestUtils.setField(consumer, "userEventCareClient", client);

        when(worlds.getById(10L)).thenReturn(new UserWorldPrefix().setId(10L).setAcitvePushStatus(enabled));
        when(characters.getOne(any())).thenReturn(new UserCharacterInfo());
        when(events.listByIds(List.of(30L))).thenReturn(List.of(new UserEventLog()
                .setId(30L).setUserWorldId(10L).setCharacterId(20L).setEventDescription("明天考试")));
        when(events.lambdaQuery()).thenAnswer(invocation -> new LambdaQueryChainWrapper<>(eventMapper));
        when(eventMapper.selectList(any())).thenReturn(List.of());
        when(client.prompt()).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.content())
                .thenReturn(hasEvent ? "考试顺利吗？" : "{\"topic\":\"勇气\",\"message\":\"你如何理解勇气？\"}");
        var task = new UserEventLogDelayTaskDTO().setUserWorldId(10L).setCharacterId(20L)
                .setTaskType(taskType).setUserEventLogIds(hasEvent ? List.of(30L) : List.of());

        ReflectionTestUtils.invokeMethod(consumer, "handleUserEventLogTask", task);

        if (enabled) {
            String expected = hasEvent ? "考试顺利吗？" : "你如何理解勇气？";
            verify(histories).insert(any(UserChatHistory.class));
            verify(socket).sendMessageToSession(argThat(message ->
                    message.getUserWorldId().equals(10L) && message.getCharacterId().equals(20L)
                            && expected.equals(message.getContent())));
            verify(topics).startAssistantMessageTopic(any(UserChatHistory.class));
        } else {
            verifyNoInteractions(histories, socket, topics);
            verify(client, never()).prompt();
            verify(events, never()).addUserEventLog(any());
        }
    }
}
