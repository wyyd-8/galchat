package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class TrpgProposalOrderServiceTest {

    @Test
    void validCachedGlobalOrderChoosesTheFirstActorPresentInTheScene() {
        TrpgProposalOrderStore store =
                mock(TrpgProposalOrderStore.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        when(turnMapper.selectLatestCompletedSceneProposalTurnId(51L))
                .thenReturn(40L);
        when(store.load(51L)).thenReturn(Optional.of(
                new TrpgProposalOrderStore.State(
                        40L,
                        List.of("character-card:112", "character-card:71",
                                "character-card:111"))));
        TrpgProposalOrderService service =
                new TrpgProposalOrderService(
                        store,
                        turnMapper,
                        mock(GroupChatReplyStepMapper.class),
                        mock(TrpgParticipantService.class));

        List<GroupActionSpec> ordered = service.orderForTurn(
                new GroupConversation().setId(51L),
                List.of(
                        action(GroupChatConstant.ACTOR_USER, 71L, 71L, 1),
                        action(GroupChatConstant.ACTOR_CHARACTER, 11L, 111L, 2),
                        action(GroupChatConstant.ACTOR_CHARACTER, 12L, 112L, 3),
                        action(GroupChatConstant.ACTOR_KP, null, 4)));

        assertThat(ordered)
                .extracting(GroupActionSpec::actorType,
                        GroupActionSpec::actorId,
                        GroupActionSpec::itemOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_CHARACTER, 12L, 1),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_USER, 71L, 2),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_CHARACTER, 11L, 3),
                        org.assertj.core.groups.Tuple.tuple(
                                GroupChatConstant.ACTOR_KP, null, 4));
    }

    @Test
    void investigatorMissingFromValidCacheIsAppendedWhenEnteringAPlan() {
        TrpgProposalOrderStore store =
                mock(TrpgProposalOrderStore.class);
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        when(turnMapper.selectLatestCompletedSceneProposalTurnId(51L))
                .thenReturn(40L);
        when(store.load(51L)).thenReturn(Optional.of(
                new TrpgProposalOrderStore.State(
                        40L, List.of("character-card:71", "character-card:111"))));
        TrpgProposalOrderService service =
                new TrpgProposalOrderService(
                        store,
                        turnMapper,
                        mock(GroupChatReplyStepMapper.class),
                        mock(TrpgParticipantService.class));

        List<GroupActionSpec> ordered = service.orderForTurn(
                new GroupConversation().setId(51L),
                List.of(
                        action(GroupChatConstant.ACTOR_CHARACTER, 12L, 112L, 1),
                        action(GroupChatConstant.ACTOR_USER, 71L, 71L, 2),
                        action(GroupChatConstant.ACTOR_KP, null, 3)));

        assertThat(ordered).extracting(GroupActionSpec::actorId)
                .containsExactly(71L, 12L, null);
        verify(store).save(51L, new TrpgProposalOrderStore.State(
                40L,
                List.of("character-card:71", "character-card:111",
                        "character-card:112")));
    }

    @Test
    void staleCacheRebuildsByReplayingCompletedProposalTurns() {
        InMemoryStore store = new InMemoryStore();
        store.save(51L, new TrpgProposalOrderStore.State(
                20L,
                List.of("character-card:71", "character-card:111",
                        "character-card:112")));
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        when(turnMapper.selectLatestCompletedSceneProposalTurnId(51L))
                .thenReturn(30L);
        when(turnMapper.selectList(any())).thenReturn(List.of(
                completedTurn(10L), completedTurn(20L),
                completedTurn(30L)));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                completedLead(10L, GroupChatConstant.ACTOR_CHARACTER, 11L),
                completedLead(20L, GroupChatConstant.ACTOR_USER, 71L),
                completedLead(30L, GroupChatConstant.ACTOR_CHARACTER, 12L)));
        GroupConversation conversation = new GroupConversation().setId(51L);
        when(participantService.listInvestigators(conversation))
                .thenReturn(List.of(
                        participant(GroupChatConstant.ACTOR_USER, 71L),
                        participant(GroupChatConstant.ACTOR_CHARACTER, 11L),
                        participant(GroupChatConstant.ACTOR_CHARACTER, 12L)));
        TrpgProposalOrderService service =
                new TrpgProposalOrderService(
                        store, turnMapper, stepMapper,
                        participantService);

        List<GroupActionSpec> ordered = service.orderForTurn(
                conversation,
                List.of(
                        action(GroupChatConstant.ACTOR_USER, 71L, 71L, 1),
                        action(GroupChatConstant.ACTOR_CHARACTER, 12L, 112L, 2),
                        action(GroupChatConstant.ACTOR_CHARACTER, 11L, 111L, 3),
                        action(GroupChatConstant.ACTOR_KP, null, 4)));

        assertThat(ordered).extracting(GroupActionSpec::actorId)
                .containsExactly(11L, 71L, 12L, null);
        assertThat(store.load(51L)).contains(
                new TrpgProposalOrderStore.State(
                        30L,
                        List.of("character-card:111", "character-card:71",
                                "character-card:112")));
    }

    @Test
    void completedSceneTurnMovesItsActualLeadToTheGlobalQueueTail() {
        InMemoryStore store = new InMemoryStore();
        store.save(51L, new TrpgProposalOrderStore.State(
                40L,
                List.of("character-card:112", "character-card:71",
                        "character-card:111")));
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        when(turnMapper.selectLatestCompletedSceneProposalTurnId(51L))
                .thenReturn(40L);
        when(stepMapper.selectList(any())).thenReturn(List.of(
                completedLead(41L,
                        GroupChatConstant.ACTOR_CHARACTER, 12L)));
        TrpgProposalOrderService service =
                new TrpgProposalOrderService(
                        store, turnMapper, stepMapper,
                        mock(TrpgParticipantService.class));

        service.onTurnCompleted(
                new GroupConversation().setId(51L),
                completedTurn(41L));

        assertThat(store.load(51L)).contains(
                new TrpgProposalOrderStore.State(
                        41L,
                        List.of("character-card:71",
                                "character-card:111",
                                "character-card:112")));
    }

    @Test
    void redisFailureFallsBackToDatabaseOrderWithoutBlockingTheTurn() {
        GroupChatTurnMapper turnMapper = mock(GroupChatTurnMapper.class);
        GroupChatReplyStepMapper stepMapper =
                mock(GroupChatReplyStepMapper.class);
        TrpgParticipantService participantService =
                mock(TrpgParticipantService.class);
        when(turnMapper.selectLatestCompletedSceneProposalTurnId(51L))
                .thenReturn(0L);
        when(turnMapper.selectList(any())).thenReturn(List.of());
        GroupConversation conversation = new GroupConversation().setId(51L);
        when(participantService.listInvestigators(conversation))
                .thenReturn(List.of(
                        participant(GroupChatConstant.ACTOR_USER, 71L),
                        participant(GroupChatConstant.ACTOR_CHARACTER, 11L)));
        TrpgProposalOrderService service =
                new TrpgProposalOrderService(
                        new UnavailableStore(), turnMapper, stepMapper,
                        participantService);

        List<GroupActionSpec> ordered = service.orderForTurn(
                conversation,
                List.of(
                        action(GroupChatConstant.ACTOR_CHARACTER, 11L, 111L, 1),
                        action(GroupChatConstant.ACTOR_USER, 71L, 71L, 2),
                        action(GroupChatConstant.ACTOR_KP, null, 3)));

        assertThat(ordered).extracting(GroupActionSpec::actorId)
                .containsExactly(71L, 11L, null);
    }

    private GroupActionSpec action(
            String actorType, Long actorId, int itemOrder) {
        return action(actorType, actorId, null, itemOrder);
    }

    private GroupActionSpec action(
            String actorType, Long actorId, Long cardId, int itemOrder) {
        return new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                actorType,
                actorId,
                cardId,
                "scene:301",
                "书房",
                1,
                itemOrder);
    }

    private GroupChatTurn completedTurn(Long id) {
        return new GroupChatTurn()
                .setId(id)
                .setConversationId(51L)
                .setPlanSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
    }

    private GroupChatReplyStep completedLead(
            Long turnId, String actorType, Long actorId) {
        return new GroupChatReplyStep()
                .setTurnId(turnId)
                .setStepNo(1)
                .setActionType(GroupChatConstant.ACTION_TRPG_SCENE)
                .setSpeakerType(actorType)
                .setSpeakerId(actorId)
                .setSubjectCharacterId(
                        GroupChatConstant.ACTOR_USER.equals(actorType)
                                ? 71L
                                : GroupChatConstant.ACTOR_CHARACTER.equals(
                                actorType) ? actorId + 100L : null)
                .setStatus(GroupChatConstant.STATUS_COMPLETED);
    }

    private TrpgParticipantService.Participant participant(
            String actorType, Long actorId) {
        return new TrpgParticipantService.Participant(
                new GroupActorRef(actorType, actorId),
                GroupChatConstant.ACTOR_USER.equals(actorType)
                        ? actorId : actorId + 100L,
                actorType + actorId,
                actorType);
    }

    private static final class InMemoryStore
            implements TrpgProposalOrderStore {
        private State state;

        @Override
        public Optional<State> load(Long conversationId) {
            return Optional.ofNullable(state);
        }

        @Override
        public void save(Long conversationId, State state) {
            this.state = state;
        }

        @Override
        public void evict(Long conversationId) {
            state = null;
        }
    }

    private static final class UnavailableStore
            implements TrpgProposalOrderStore {
        @Override
        public Optional<State> load(Long conversationId) {
            throw new IllegalStateException("redis unavailable");
        }

        @Override
        public void save(Long conversationId, State state) {
            throw new IllegalStateException("redis unavailable");
        }

        @Override
        public void evict(Long conversationId) {
            throw new IllegalStateException("redis unavailable");
        }
    }
}
