package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationLockService;
import com.me.galchat.service.impl.group.GroupTurnRecoveryService;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgGameTimeUpdateDTO;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.service.IUserWorldPrefixService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgGameTimeServiceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(
                        new MybatisConfiguration(), ""),
                GroupConversation.class);
    }

    @Test
    void manualCorrectionCannotInitializeRunTime() {
        GroupConversationMapper mapper =
                mock(GroupConversationMapper.class);
        IUserWorldPrefixService worldService =
                mock(IUserWorldPrefixService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupConversation conversation = conversation();
        when(mapper.selectById(7L)).thenReturn(conversation);
        TrpgGameTimeService service = new TrpgGameTimeService(
                mapper, worldService,
                mock(GroupTurnRecoveryService.class), lockService);

        assertThatThrownBy(() -> service.correct(
                12L, 7L,
                new TrpgGameTimeUpdateDTO(
                        1, "MORNING", 0)))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("当前时间尚未由KP初始化");

        verify(worldService).checkUserWorldAuth(12L, 5L, true);
        verify(lockService, never()).tryLock(any());
    }

    @Test
    void manualCorrectionMayMoveBackwardAndClearsKpStepMarker() {
        GroupConversationMapper mapper =
                mock(GroupConversationMapper.class);
        IUserWorldPrefixService worldService =
                mock(IUserWorldPrefixService.class);
        GroupTurnRecoveryService recoveryService =
                mock(GroupTurnRecoveryService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupConversation conversation = conversation()
                .setGameDayNo(3)
                .setGameTimePeriod("EVENING")
                .setGameTimeRevision(4)
                .setGameTimeChangedStepId(31L);
        when(mapper.selectById(7L)).thenReturn(conversation);
        GroupConversationLockService.OwnedLock lock =
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L);
        when(lockService.tryLock(7L)).thenReturn(lock);
        when(mapper.update(
                isNull(),
                any(com.baomidou.mybatisplus.core.conditions
                        .update.LambdaUpdateWrapper.class)))
                .thenReturn(1);
        TrpgGameTimeService service = new TrpgGameTimeService(
                mapper, worldService, recoveryService, lockService);

        var result = service.correct(
                12L, 7L,
                new TrpgGameTimeUpdateDTO(
                        2, "MORNING", 4));

        assertThat(result.displayText())
                .isEqualTo("第二天 - 上午");
        assertThat(result.revision()).isEqualTo(5);
        assertThat(conversation.getGameTimeChangedStepId()).isNull();
        verify(recoveryService)
                .assertConversationHasNoNonTerminalTurns(7L);
        verify(lockService).unlock(lock);
    }

    @Test
    void manualCorrectionRejectsStaleRevisionAfterLock() {
        GroupConversationMapper mapper =
                mock(GroupConversationMapper.class);
        IUserWorldPrefixService worldService =
                mock(IUserWorldPrefixService.class);
        GroupTurnRecoveryService recoveryService =
                mock(GroupTurnRecoveryService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupConversation beforeLock = conversation()
                .setGameDayNo(1)
                .setGameTimePeriod("MORNING")
                .setGameTimeRevision(2);
        GroupConversation afterLock = conversation()
                .setGameDayNo(1)
                .setGameTimePeriod("AFTERNOON")
                .setGameTimeRevision(3);
        when(mapper.selectById(7L))
                .thenReturn(beforeLock, afterLock);
        GroupConversationLockService.OwnedLock lock =
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L);
        when(lockService.tryLock(7L)).thenReturn(lock);
        TrpgGameTimeService service = new TrpgGameTimeService(
                mapper, worldService, recoveryService, lockService);

        assertThatThrownBy(() -> service.correct(
                12L, 7L,
                new TrpgGameTimeUpdateDTO(
                        2, "MORNING", 2)))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("当前时间已发生变化，请刷新后重试");

        verify(mapper, never()).update(
                isNull(), any(com.baomidou.mybatisplus.core.conditions
                        .update.LambdaUpdateWrapper.class));
        verify(lockService).unlock(lock);
    }

    private GroupConversation conversation() {
        return new GroupConversation()
                .setId(7L)
                .setUserWorldId(5L)
                .setModuleId(3L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
    }
}
