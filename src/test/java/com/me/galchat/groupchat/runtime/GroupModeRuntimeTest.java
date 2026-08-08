package com.me.galchat.groupchat.runtime;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.context.GroupTopicService;
import com.me.galchat.groupchat.runtime.chat.ChatGroupAgentPolicy;
import com.me.galchat.groupchat.runtime.chat.ChatGroupContextPolicy;
import com.me.galchat.groupchat.runtime.chat.ChatGroupRuntime;
import com.me.galchat.groupchat.runtime.trpg.TrpgGroupAgentPolicy;
import com.me.galchat.groupchat.runtime.trpg.TrpgGroupContextPolicy;
import com.me.galchat.groupchat.runtime.trpg.TrpgGroupRuntime;
import com.me.galchat.service.impl.GroupContextAssembler;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.CharacterCardContextFormatter;
import com.me.galchat.tool.UserCharacterFavorTools;
import com.me.galchat.tool.UserCharacterInfoTools;
import com.me.galchat.tool.VectorTools;
import com.me.galchat.tool.KpDiceTools;
import com.me.galchat.vector.GroupTopicVectorService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupModeRuntimeTest {

    @Test
    void chatAndTrpgBindDifferentAgentContextAndTurnSlots() {
        ChatGroupRuntime chat = new ChatGroupRuntime(
                mock(GroupTurnPolicy.class), mock(GroupContextPolicy.class), mock(GroupAgentPolicy.class));
        TrpgGroupRuntime trpg = new TrpgGroupRuntime(
                mock(GroupTurnPolicy.class), mock(GroupContextPolicy.class), mock(GroupAgentPolicy.class));

        assertThat(chat.mode()).isEqualTo(GroupChatConstant.MODE_CHAT);
        assertThat(trpg.mode()).isEqualTo(GroupChatConstant.MODE_TRPG);
        assertThat(chat.turnPolicy()).isNotSameAs(trpg.turnPolicy());
        assertThat(chat.contextPolicy()).isNotSameAs(trpg.contextPolicy());
        assertThat(chat.agentPolicy()).isNotSameAs(trpg.agentPolicy());
    }

    @Test
    void contextPoliciesOwnTheirModeSpecificPreparation() {
        GroupContextAssembler assembler = mock(GroupContextAssembler.class);
        GroupTopicService topicService = mock(GroupTopicService.class);
        GroupTopicVectorService vectorService = mock(GroupTopicVectorService.class);
        GroupConversation chat = new GroupConversation().setId(1L).setMode(GroupChatConstant.MODE_CHAT);
        GroupConversation trpg = new GroupConversation().setId(2L).setMode(GroupChatConstant.MODE_TRPG);
        GroupChatMessage userMessage = new GroupChatMessage().setSequenceNo(20L).setContent("继续调查仓库");
        GroupActionSpec action = new GroupActionSpec(
                GroupChatConstant.ACTION_CHAT_REPLY, GroupChatConstant.ACTOR_CHARACTER, 11L,
                "default", "群聊", 1, 1);
        GroupActorRef actor = action.actor();
        when(topicService.windowStartSequence(chat)).thenReturn(10L);
        when(assembler.assembleContextFrom(chat, actor, 10L))
                .thenReturn(List.of(new UserMessage("上一话题"), new UserMessage("继续调查仓库")));
        when(vectorService.search(1L, 10L, "上一话题\n继续调查仓库"))
                .thenReturn("更早话题的相关记忆");
        com.me.galchat.service.impl.TrpgExplorationContextAssembler
                explorationAssembler = mock(
                com.me.galchat.service.impl
                        .TrpgExplorationContextAssembler.class);
        when(explorationAssembler.assemble(trpg, actor))
                .thenReturn(List.of(new UserMessage("跑团上下文")));

        ChatGroupContextPolicy chatPolicy = new ChatGroupContextPolicy(topicService, vectorService, assembler);
        TrpgGroupContextPolicy trpgPolicy = new TrpgGroupContextPolicy(
                mock(com.me.galchat.service.impl.TrpgModuleContextAssembler.class),
                mock(com.me.galchat.service.impl
                        .TrpgAgentDecisionContextAssembler.class),
                explorationAssembler,
                mock(com.me.galchat.service.impl
                        .TrpgSceneRuntimeContextAssembler.class),
                mock(com.me.galchat.service.impl
                        .TrpgGameTimeContextAssembler.class));

        chatPolicy.onTurnStarted(chat, userMessage);
        trpgPolicy.onTurnStarted(trpg, userMessage);

        assertThat(chatPolicy.load(chat, action).messages()).extracting(message -> message.getText())
                .containsExactly("<retrieved-group-memory>\n更早话题的相关记忆\n</retrieved-group-memory>",
                        "上一话题", "继续调查仓库");
        assertThat(trpgPolicy.load(trpg, action).messages()).extracting(message -> message.getText())
                .containsExactly("跑团上下文");
        verify(topicService).onTurnStarted(chat, userMessage);
    }

    @Test
    void agentPoliciesOwnModelPromptAndToolSet() {
        ChatClient client = mock(ChatClient.class);
        GroupContextAssembler assembler = mock(GroupContextAssembler.class);
        VectorTools vectorTools = mock(VectorTools.class);
        UserCharacterFavorTools favorTools = mock(UserCharacterFavorTools.class);
        UserCharacterInfoTools infoTools = mock(UserCharacterInfoTools.class);
        GroupConversation conversation = new GroupConversation().setId(1L);
        GroupContextMaterial context = new GroupContextMaterial(List.of(new UserMessage("共享上下文")));
        GroupActorRef alice = new GroupActorRef(GroupChatConstant.ACTOR_CHARACTER, 11L);
        when(assembler.baseSystemPrompt(conversation, alice)).thenReturn("角色基础提示词");
        when(assembler.actorName(conversation.getUserWorldId(), alice)).thenReturn("Alice");
        ICharacterCardService cardService = mock(ICharacterCardService.class);
        when(cardService.listDiceCharacters(conversation.getId())).thenReturn(List.of());

        GroupModelInvocation chat = new ChatGroupAgentPolicy(
                client, assembler, vectorTools, favorTools).prepare(
                conversation,
                new GroupActionSpec(GroupChatConstant.ACTION_CHAT_REPLY,
                        GroupChatConstant.ACTOR_CHARACTER, 11L, "default", "群聊", 1, 1),
                context);
        GroupModelInvocation trpg = new TrpgGroupAgentPolicy(
                client,
                assembler,
                cardService,
                new CharacterCardContextFormatter(),
                mock(KpDiceTools.class),
                mock(com.me.galchat.tool.KpPushedCheckTools.class),
                mock(com.me.galchat.tool.TrpgSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpSceneSelectionTools.class),
                mock(com.me.galchat.tool.KpModuleTools.class),
                mock(com.me.galchat.tool.KpSkillRuleTools.class),
                mock(com.me.galchat.tool.InvestigatorSceneTools.class),
                mock(com.me.galchat.tool.KpSceneTools.class),
                mock(com.me.galchat.tool.KpRunTools.class),
                mock(com.me.galchat.service.impl.TrpgContextWindowService.class),
                mock(com.me.galchat.service.impl.TrpgInvestigatorContextAssembler.class),
                mock(com.me.galchat.tool.KpCombatTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgCombatLifecycleService.class),
                mock(com.me.galchat.tool.KpChildSceneTools.class),
                mock(com.me.galchat.tool
                        .KpWaitingInvestigatorTools.class),
                mock(com.me.galchat.service.impl
                        .TrpgChildSceneCommandService.class)).prepare(
                conversation,
                new GroupActionSpec(GroupChatConstant.ACTION_TRPG_COMBAT,
                        GroupChatConstant.ACTOR_CHARACTER, 11L, "round:1", "第1轮", 1, 1),
                context);

        assertThat(chat.prompt().getInstructions().getFirst().getText()).contains("多人群聊");
        assertThat(trpg.prompt().getInstructions().getFirst().getText()).contains("TRPG", "战斗");
        assertThat(chat.tools()).containsExactly(vectorTools, favorTools);
        assertThat(chat.tools()).doesNotContain(infoTools);
        assertThat(trpg.tools()).isEmpty();
    }
}
