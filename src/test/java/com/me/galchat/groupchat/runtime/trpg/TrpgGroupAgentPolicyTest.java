package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.CharacterCardContextFormatter;
import com.me.galchat.service.impl.GroupContextAssembler;
import com.me.galchat.tool.KpDiceTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgGroupAgentPolicyTest {

    @Test
    void kpUsesWorldPromptAllCardsAndDirectDiceInstructions() {
        ChatClient client = mock(ChatClient.class);
        GroupContextAssembler contextAssembler = mock(GroupContextAssembler.class);
        ICharacterCardService cardService = mock(ICharacterCardService.class);
        CharacterCardContextFormatter formatter = new CharacterCardContextFormatter();
        KpDiceTools kpDiceTools = mock(KpDiceTools.class);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setWorldId(2L).setUserWorldId(5L);
        GroupActorRef kp = new GroupActorRef(GroupChatConstant.ACTOR_KP, null);
        when(contextAssembler.baseSystemPrompt(conversation, kp)).thenReturn("仅世界提示词");
        when(cardService.listDiceCharacters(5L)).thenReturn(List.of(card("林恩", null)));

        TrpgGroupAgentPolicy policy =
                new TrpgGroupAgentPolicy(
                        client, contextAssembler, cardService, formatter, kpDiceTools);
        var invocation = policy.prepare(
                conversation,
                new GroupActionSpec(
                        GroupChatConstant.ACTION_TRPG_SCENE,
                        GroupChatConstant.ACTOR_KP,
                        null,
                        "scene:1",
                        "地下室",
                        1,
                        1),
                new GroupContextMaterial(List.of()));

        assertThat(invocation.prompt().getInstructions().getFirst().getText())
                .contains("仅世界提示词")
                .contains("林恩")
                .contains("KP不是可见的调查员")
                .contains("最多调用一个会改变状态的掷骰工具")
                .contains("不得继续输出叙事或JSON");
        assertThat(policy.actorName(5L, kp)).isEqualTo("KP");
        assertThat(invocation.tools()).containsExactly(kpDiceTools);
    }

    private CocDiceCharacterVO card(String name, Long participantId) {
        return new CocDiceCharacterVO(
                71L, participantId, name, Map.of("CON", 55),
                10, 10, 54, 60, 55, 0,
                false, false, false, false,
                false, null, null);
    }
}
