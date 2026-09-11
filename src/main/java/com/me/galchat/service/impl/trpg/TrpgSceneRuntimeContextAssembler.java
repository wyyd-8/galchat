package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TrpgSceneRuntimeContextAssembler {

    private final TrpgSceneParticipantService participantService;

    public String format(
            GroupConversation conversation,
            GroupActionSpec action) {
        return assemble(conversation, action).prompt();
    }

    public RuntimeContext assemble(
            GroupConversation conversation,
            GroupActionSpec action) {
        TrpgSceneParticipantService.SceneState state =
                participantService.state(conversation);
        return new RuntimeContext(
                format(state, action),
                Set.copyOf(state.activeInvestigatorCharacterIds()));
    }

    private String format(
            TrpgSceneParticipantService.SceneState state,
            GroupActionSpec action) {
        StringBuilder result = new StringBuilder(
                "<current-scene-runtime>\n当前场景：")
                .append(state.scenePath()).append("\n\n");
        boolean intro = GroupChatConstant.ACTION_TRPG_SCENE_INTRO
                .equals(action.actionType());
        if (intro) {
            result.append("当前为场景引入轮。\n")
                    .append("引入完成后参与行动的调查员：\n");
        } else {
            result.append("本轮参与行动的调查员：\n");
        }
        appendNames(result, state.activeInvestigatorNames());
        if (!state.waitingInvestigatorNames().isEmpty()) {
            result.append("\n等待中的调查员：\n");
            appendNames(result,
                    state.waitingInvestigatorNames());
            result.append("""

                    等待中的调查员此前已经在子场景中独立行动了一段时间。
                    请根据当前剧情、时间经过、位置关系和队伍行动，
                    选择自然且合适的汇合时机调用 resumeWaitingInvestigators。
                    工具生效后，他们将从下一轮开始恢复行动。
                    """);
        }
        return result.append("</current-scene-runtime>")
                .toString();
    }

    public record RuntimeContext(
            String prompt,
            Set<Long> activeInvestigatorCharacterIds) {
    }

    private void appendNames(
            StringBuilder result, List<String> names) {
        if (names.isEmpty()) {
            result.append("- 无\n");
            return;
        }
        names.forEach(name ->
                result.append("- ").append(name).append('\n'));
    }
}
