package com.me.galchat.service.impl.group;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.*;
import com.me.galchat.exception.GroupCheckpointUnavailableException;
import com.me.galchat.groupchat.dice.DiceRollMessageCodec;
import com.me.galchat.mapper.*;
import com.me.galchat.service.ICharacterCardService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupChatCheckpointServiceTest {
    @BeforeAll
    static void initTables() {
        com.me.galchat.support.MybatisPlusTestSupport.initialize(
                GroupChatReplyStep.class, GroupChatMessage.class, GroupChatToolCall.class);
    }

    final GroupTurnCheckpointMapper checkpoints = mock(GroupTurnCheckpointMapper.class);
    final GroupChatMessageMapper messages = mock(GroupChatMessageMapper.class);
    final GroupChatToolCallMapper tools = mock(GroupChatToolCallMapper.class);
    final GroupChatReplyStepMapper steps = mock(GroupChatReplyStepMapper.class);
    final GroupChatTurnMapper turns = mock(GroupChatTurnMapper.class);
    final GroupChatFavorRollbackService favor = mock(GroupChatFavorRollbackService.class);
    final GroupTurnCheckpointService service = new GroupTurnCheckpointService(checkpoints, messages,
            tools, steps, turns, mock(DiceRollSummaryMapper.class), mock(DiceRollMessageCodec.class),
            mock(ICharacterCardService.class), JsonMapper.builder().build(), favor,
            mock(com.me.galchat.service.impl.trpg.TrpgEquipmentService.class),
            mock(com.me.galchat.service.impl.trpg.TrpgMaterialRecoveryService.class),
                        mock(com.me.galchat.service.impl.trpg.TrpgInvestigatorSuspensionService.class));
    final GroupConversation conversation = new GroupConversation().setId(7L).setUserWorldId(5L).setMode("chat");
    final GroupChatTurn turn = new GroupChatTurn().setId(101L).setConversationId(7L).setStatus("failed");
    final GroupChatReplyStep completed = step(102L, 1, "completed");
    final GroupChatReplyStep failed = step(103L, 2, "failed");
    final GroupChatReplyStep cancelled = step(104L, 3, "cancelled");

    private GroupChatReplyStep step(long id, int no, String status) {
        return new GroupChatReplyStep().setId(id).setTurnId(101L).setStepNo(no)
                .setStatus(status).setOutputMessageId(id + 100).setErrorMessage("模型失败");
    }

    private GroupTurnCheckpoint checkpoint(String type, long stepId) {
        return new GroupTurnCheckpoint().setConversationId(7L).setTurnId(101L)
                .setReplyStepId(stepId).setCheckpointType(type).setMessageId(202L).setToolCallId(9L);
    }

    @Test
    void completedBoundaryKeepsEarlierReplyAndOnlyRestoresTheSuffix() {
        when(checkpoints.selectById(7L)).thenReturn(checkpoint("COMPLETED", 102L));
        when(steps.selectList(any())).thenReturn(List.of(completed, failed, cancelled));

        assertThat(service.restoreChat(conversation, turn)).containsExactly(failed, cancelled);

        assertThat(completed.getStatus()).isEqualTo("completed");
        assertThat(completed.getOutputMessageId()).isEqualTo(202L);
        assertThat(List.of(failed, cancelled)).allSatisfy(step -> {
            assertThat(step.getStatus()).isEqualTo("pending");
            assertThat(step.getOutputMessageId()).isNull();
            assertThat(step.getErrorMessage()).isNull();
        });
        assertThat(turn.getStatus()).isEqualTo("running");
        var ordered = inOrder(favor, messages, tools, steps, turns);
        ordered.verify(favor).rollback(5L, List.of(103L, 104L));
        ordered.verify(messages).delete(argThat((LambdaQueryWrapper<GroupChatMessage> query) -> {
            query.getSqlSegment();
            return query.getParamNameValuePairs().values().containsAll(List.of(103L, 104L))
                    && !query.getParamNameValuePairs().values().contains(102L);
        }));
        ordered.verify(tools).delete(any());
        ordered.verify(steps, times(2)).update(isNull(), any());
        ordered.verify(turns).updateById(turn);
        verify(checkpoints, never()).deleteById(anyLong());
    }

    @Test
    void initialBoundaryAllowsRetryOfTheFirstCharacter() {
        failed.setStepNo(1);
        when(checkpoints.selectById(7L)).thenReturn(checkpoint("STEP_START", 103L));
        when(steps.selectList(any())).thenReturn(List.of(failed, cancelled));
        assertThat(service.restoreChat(conversation, turn)).containsExactly(failed, cancelled);
        verify(favor).rollback(5L, List.of(103L, 104L));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryReadsCurrentModelSelectionAndPreservesCompletedSnapshot(boolean useDefaultModel) {
        completed.setExecutionMode("model").setModelApiId(41L);
        failed.setSpeakerType("character").setSpeakerId(12L)
                .setExecutionMode("model").setModelApiId(41L);
        cancelled.setSpeakerType("character").setSpeakerId(13L)
                .setExecutionMode("model").setModelApiId(41L);
        when(checkpoints.selectById(7L)).thenReturn(checkpoint("COMPLETED", 102L));
        when(steps.selectList(any())).thenReturn(List.of(completed, failed, cancelled));
        var configs = mock(GroupActorRuntimeConfigMapper.class);
        Long selectedModel = useDefaultModel ? null : 55L;
        when(configs.selectByActorKey(eq(7L), anyString())).thenReturn(
                new GroupActorRuntimeConfig().setControlMode("model").setModelApiId(selectedModel));
        var runtime = new GroupActorRuntimeService(null, configs, steps, null, null, null, null);

        var remaining = service.restoreChat(conversation, turn);

        assertThat(remaining).containsExactly(failed, cancelled);
        assertThat(remaining).allSatisfy(step -> {
            assertThat(runtime.snapshot(conversation, step).modelApiId()).isEqualTo(selectedModel);
        });
        assertThat(completed.getModelApiId()).isEqualTo(41L);
        assertThat(completed.getExecutionMode()).isEqualTo("model");
        verify(configs).selectByActorKey(7L, "character:12");
        verify(configs).selectByActorKey(7L, "character:13");
        verify(steps, times(2)).update(isNull(), argThat((LambdaUpdateWrapper<GroupChatReplyStep> update) -> {
            String sql = update.getSqlSet();
            var parameters = update.getParamNameValuePairs();
            return List.of("execution_mode", "model_api_id").stream().allMatch(column -> {
                var matcher = java.util.regex.Pattern.compile(column + "=#\\{ew.paramNameValuePairs.(\\w+)\\}")
                        .matcher(sql);
                return matcher.find() && parameters.containsKey(matcher.group(1))
                        && parameters.get(matcher.group(1)) == null;
            });
        }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "other-turn", "other-step", "wrong-type", "unfinished-prefix", "completed-tail", "later-start"})
    void invalidBoundaryDoesNotMutateAnything(String scenario) {
        GroupTurnCheckpoint checkpoint = checkpoint("COMPLETED", 102L);
        switch (scenario) {
            case "missing" -> checkpoint = null;
            case "other-turn" -> checkpoint.setTurnId(99L);
            case "other-step" -> checkpoint.setReplyStepId(999L);
            case "wrong-type" -> checkpoint.setCheckpointType("PAUSED");
            case "unfinished-prefix" -> completed.setStatus("failed");
            case "completed-tail" -> cancelled.setStatus("completed");
            case "later-start" -> checkpoint.setCheckpointType("STEP_START").setReplyStepId(103L);
        }
        when(checkpoints.selectById(7L)).thenReturn(checkpoint);
        when(steps.selectList(any())).thenReturn(List.of(completed, failed, cancelled));
        assertThatThrownBy(() -> service.restoreChat(conversation, turn))
                .isInstanceOf(GroupCheckpointUnavailableException.class).hasMessageContaining("撤回本轮对话");
        verifyNoInteractions(messages, tools, turns, favor);
        verify(steps, never()).update(any(), any());
        assertThat(turn.getStatus()).isEqualTo("failed");
    }
}
