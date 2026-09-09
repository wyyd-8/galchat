package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgEpilogueModels;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgEpilogueServiceTest {

    @Test
    void persistsOneCharacterFocusedEpilogueEntryPerInvestigator() throws Exception {
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        CocCharacterProfileMapper profileMapper =
                mock(CocCharacterProfileMapper.class);
        GroupChatMessageMapper messageMapper =
                mock(GroupChatMessageMapper.class);
        GroupConversationService conversationService =
                mock(GroupConversationService.class);
        TrpgExplorationContextAssembler explorationContextAssembler =
                mock(TrpgExplorationContextAssembler.class);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        RecordingGenerator generator = new RecordingGenerator();
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setTitle("古树之中");
        when(characterMapper.selectList(any())).thenReturn(List.of(
                investigator(11L, "PLAYER", "林恩", false),
                investigator(12L, "BOT", "威廉", true),
                investigator(13L, "NPC", "柯利先生", false)));
        when(profileMapper.selectList(any())).thenReturn(List.of(
                new CocCharacterProfile()
                        .setCharacterId(11L)
                        .setIdeology("真相应当被记录"),
                new CocCharacterProfile()
                        .setCharacterId(12L)
                        .setTreasuredPossessions("随身笔记")));
        GroupChatMessage finalKpMessage = new GroupChatMessage()
                .setSpeakerType(GroupChatConstant.ACTOR_KP)
                .setMessageKind(GroupChatConstant.MESSAGE_NARRATION)
                .setContent("威廉没能离开燃烧的庄园。")
                .setSequenceNo(41L)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
        when(messageMapper.selectOne(any())).thenReturn(finalKpMessage);
        when(conversationService.nextSequence(7L)).thenReturn(42L);
        ArgumentCaptor<GroupChatMessage> inserted =
                ArgumentCaptor.forClass(GroupChatMessage.class);

        TrpgEpilogueService service = new TrpgEpilogueService(
                profileMapper, messageMapper,
                conversationService, generator,
                new TrpgEpilogueMessageCodec(objectMapper));

        var subjects = service.subjects(List.of(investigator(11L, "PLAYER", "林恩", false),
                investigator(12L, "BOT", "威廉", true)));
        var materials = new com.me.galchat.domain.dto.TrpgCompletionModels.Materials("古树之中", null, 41L,
                1,
                List.of(new com.me.galchat.domain.dto.TrpgCompletionModels.Source(1L, 41L, "庄园调查摘要")),
                subjects.stream().map(subject -> new com.me.galchat.domain.dto.TrpgCompletionModels.Investigator(
                        subject, null, false, null, null)).toList(), List.of(), List.of());
        var client = mock(org.springframework.ai.chat.client.ChatClient.class);
        var entries = service.generate(client, conversation, materials);
        assertThat(generator.client).isSameAs(client);
        service.persist(conversation, entries, 10L, 11L);

        org.mockito.Mockito.verify(messageMapper).insert(inserted.capture());
        GroupChatMessage message = inserted.getValue();
        assertThat(message.getMessageKind())
                .isEqualTo(GroupChatConstant.MESSAGE_EPILOGUE);
        assertThat(message.getSpeakerType())
                .isEqualTo(GroupChatConstant.ACTOR_KP);
        assertThat(message.getSequenceNo()).isEqualTo(42L);
        TrpgEpilogueModels.Content content = objectMapper.readValue(
                message.getContent(), TrpgEpilogueModels.Content.class);
        assertThat(content.schemaVersion()).isEqualTo(1);
        assertThat(content.entries())
                .extracting(TrpgEpilogueModels.Entry::characterId,
                        TrpgEpilogueModels.Entry::investigatorName,
                        TrpgEpilogueModels.Entry::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                11L, "林恩", "林恩重新回到了报社。"),
                        org.assertj.core.groups.Tuple.tuple(
                                12L, "威廉", "威廉的笔记被妹妹保存了下来。"));
        assertThat(generator.subjects)
                .extracting(TrpgEpilogueModels.Subject::characterId,
                        TrpgEpilogueModels.Subject::dead)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(11L, false),
                        org.assertj.core.groups.Tuple.tuple(12L, true));
        assertThat(generator.history).isEqualTo("庄园调查摘要");
    }

    private CocCharacter investigator(
            long id, String actorType, String name, boolean dead) {
        return new CocCharacter()
                .setId(id)
                .setRunId(7L)
                .setActorType(actorType)
                .setName(name)
                .setOccupation("记者")
                .setHpCurrent(dead ? 0 : 7)
                .setHpMax(10)
                .setSanCurrent(42)
                .setSanMax(70)
                .setDead(dead);
    }

    private static final class RecordingGenerator
            implements TrpgEpilogueGenerator {

        private List<TrpgEpilogueModels.Subject> subjects = List.of();
        private String history;
        private org.springframework.ai.chat.client.ChatClient client;

        @Override
        public TrpgEpilogueModels.Response generate(
                org.springframework.ai.chat.client.ChatClient client,
                GroupConversation conversation,
                List<TrpgEpilogueModels.Subject> subjects,
                String publicHistory) {
            this.client = client;
            this.subjects = List.copyOf(subjects);
            this.history = publicHistory;
            return new TrpgEpilogueModels.Response(List.of(
                    new TrpgEpilogueModels.Entry(
                            11L, "忽略模型名称", "回到报社", "林恩重新回到了报社。"),
                    new TrpgEpilogueModels.Entry(
                            12L, "忽略模型名称", "留下笔记", "威廉的笔记被妹妹保存了下来。")));
        }
    }
}
