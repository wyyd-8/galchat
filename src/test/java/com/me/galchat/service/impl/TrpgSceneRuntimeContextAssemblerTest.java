package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgSceneRuntimeContextAssemblerTest {

    @Test
    void listsCurrentPathActiveInvestigatorsAndWaitingInvestigators() {
        TrpgSceneParticipantService participantService =
                mock(TrpgSceneParticipantService.class);
        GroupConversation conversation =
                new GroupConversation().setId(7L);
        when(participantService.state(conversation)).thenReturn(
                new TrpgSceneParticipantService.SceneState(
                        31L,
                        "摩根老大的住宅 - 书房",
                        List.of("亨利"),
                        List.of(71L),
                        List.of("艾琳", "威廉")));
        TrpgSceneRuntimeContextAssembler assembler =
                new TrpgSceneRuntimeContextAssembler(
                        participantService);

        String context = assembler.format(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE,
                        GroupChatConstant.ACTOR_KP,
                        null, "scene:21", "书房", 1, 1));

        assertThat(context)
                .contains("当前场景：摩根老大的住宅 - 书房")
                .contains("本轮参与行动的调查员：\n- 亨利")
                .contains("等待中的调查员：\n- 艾琳\n- 威廉")
                .contains("选择自然且合适的汇合时机");
    }

    @Test
    void introListsInvestigatorsThatWillActAfterIntroduction() {
        TrpgSceneParticipantService participantService =
                mock(TrpgSceneParticipantService.class);
        GroupConversation conversation =
                new GroupConversation().setId(7L);
        when(participantService.state(conversation)).thenReturn(
                new TrpgSceneParticipantService.SceneState(
                        31L,
                        "摩根老大的住宅 - 书房",
                        List.of("艾琳", "威廉"),
                        List.of(72L, 73L),
                        List.of()));
        TrpgSceneRuntimeContextAssembler assembler =
                new TrpgSceneRuntimeContextAssembler(
                        participantService);

        String context = assembler.format(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE_INTRO,
                        GroupChatConstant.ACTOR_KP,
                        null, "scene:21", "书房", 1, 0));

        assertThat(context)
                .contains("当前为场景引入轮")
                .contains("引入完成后参与行动的调查员：\n- 艾琳\n- 威廉")
                .doesNotContain("等待中的调查员");
    }

    @Test
    void assembledContextCarriesOnlyActiveInvestigatorCardIds() {
        TrpgSceneParticipantService participantService =
                mock(TrpgSceneParticipantService.class);
        GroupConversation conversation =
                new GroupConversation().setId(7L);
        when(participantService.state(conversation)).thenReturn(
                new TrpgSceneParticipantService.SceneState(
                        31L, "书房",
                        List.of("林恩"), List.of(71L),
                        List.of("艾琳")));
        TrpgSceneRuntimeContextAssembler assembler =
                new TrpgSceneRuntimeContextAssembler(participantService);

        TrpgSceneRuntimeContextAssembler.RuntimeContext context =
                assembler.assemble(
                        conversation,
                        new GroupActionSpec(
                                GroupChatConstant.ACTION_TRPG_SCENE,
                                GroupChatConstant.ACTOR_KP,
                                null, "scene:21", "书房", 1, 1));

        assertThat(context.activeInvestigatorCharacterIds())
                .containsExactly(71L);
    }
}
