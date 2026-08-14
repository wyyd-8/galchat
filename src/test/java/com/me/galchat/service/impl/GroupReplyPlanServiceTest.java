package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.me.galchat.domain.dto.GroupReplyPlanDTO;
import com.me.galchat.domain.po.GroupChatMember;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.po.TrpgRuntimeChildScene;
import com.me.galchat.domain.vo.GroupReplyPlanVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgRuntimeChildSceneMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.redisson.api.RLock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupReplyPlanServiceTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GroupReplyPlan.class);
        TableInfoHelper.initTableInfo(
                assistant, TrpgRuntimeChildScene.class);
        TableInfoHelper.initTableInfo(
                assistant, GroupReplyPlanItem.class);
        TableInfoHelper.initTableInfo(
                assistant, GroupConversation.class);
    }

    @Test
    void replyPlanQueryReturnsEveryPlanWithActivePlanFirst() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = activeConversation(
                GroupChatConstant.MODE_TRPG, 40L);
        GroupReplyPlan root = plan(
                10L, GroupChatConstant.PLAN_SOURCE_SCENE, null)
                .setNextPlanId(30L);
        GroupReplyPlan nextRoot = plan(
                30L, GroupChatConstant.PLAN_SOURCE_SCENE, null);
        GroupReplyPlan child = plan(
                20L, GroupChatConstant.PLAN_SOURCE_SCENE, null)
                .setParentPlanId(10L)
                .setNextPlanId(21L);
        GroupReplyPlan nextChild = plan(
                21L, GroupChatConstant.PLAN_SOURCE_SCENE, null)
                .setParentPlanId(10L);
        GroupReplyPlan combat = plan(
                40L, GroupChatConstant.PLAN_SOURCE_COMBAT, 20L)
                .setDisplayName("战斗第2轮");
        GroupReplyPlanItem investigator = item(201L, 20L, 9L)
                .setSubjectCharacterId(71L)
                .setSubjectCharacterName("康特·奈尔")
                .setParticipantStatus(
                        GroupChatConstant.PARTICIPANT_WAITING);
        when(fixture.conversationService.requireAuthorized(7L))
                .thenReturn(conversation);
        when(fixture.planMapper.selectById(40L)).thenReturn(combat);
        when(fixture.planMapper.selectList(any())).thenReturn(List.of(
                root, nextRoot, child, nextChild, combat));
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(
                investigator));

        Object result = fixture.service.listRemaining(7L);

        assertThat(result).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<GroupReplyPlanVO> plans =
                (List<GroupReplyPlanVO>) result;
        assertThat(plans).extracting(GroupReplyPlanVO::getId)
                .containsExactly(40L, 10L, 30L, 20L, 21L);
        assertThat(plans.get(3).getParentPlanId()).isEqualTo(10L);
        assertThat(plans.get(3).getNextPlanId()).isEqualTo(21L);
        assertThat(plans.getFirst().getResumePlanId())
                .isEqualTo(20L);
        GroupReplyPlanVO.Item item = plans.get(3)
                .getItems().getFirst();
        assertThat(item.getSubjectCharacterName())
                .isEqualTo("康特·奈尔");
        assertThat(item.getParticipantStatus())
                .isEqualTo(GroupChatConstant.PARTICIPANT_WAITING);
    }

    @Test
    void replyPlanQueryRejectsMissingActivePlan() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireAuthorized(7L))
                .thenReturn(activeConversation(
                        GroupChatConstant.MODE_TRPG, 40L));
        when(fixture.planMapper.selectList(any())).thenReturn(List.of(
                plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE, null)));

        assertThatThrownBy(() -> fixture.service.listRemaining(7L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("活动回复计划不存在");
    }

    @Test
    void replyPlanQueryReturnsEmptyListWithoutActivePlan() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireAuthorized(7L))
                .thenReturn(activeConversation(
                        GroupChatConstant.MODE_TRPG, null));
        when(fixture.planMapper.selectList(any()))
                .thenReturn(List.of());

        Object result = fixture.service.listRemaining(7L);

        assertThat(result).isEqualTo(List.of());
        verify(fixture.itemMapper, never()).selectList(any());
    }

    @Test
    void replyPlanQueryRejectsPlansWithoutActivePlanId() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireAuthorized(7L))
                .thenReturn(activeConversation(
                        GroupChatConstant.MODE_TRPG, null));
        when(fixture.planMapper.selectList(any())).thenReturn(List.of(
                plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE, null)));

        assertThatThrownBy(() -> fixture.service.listRemaining(7L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("缺少活动回复计划");
    }

    @Test
    void replyPlanQueryOmitsInternalExecutionMetadata() throws Exception {
        Fixture fixture = new Fixture();
        GroupReplyPlan active = plan(
                40L, GroupChatConstant.PLAN_SOURCE_COMBAT, 20L);
        when(fixture.conversationService.requireAuthorized(7L))
                .thenReturn(activeConversation(
                        GroupChatConstant.MODE_TRPG, 40L));
        when(fixture.planMapper.selectById(40L)).thenReturn(active);
        when(fixture.planMapper.selectList(any()))
                .thenReturn(List.of(active));
        when(fixture.itemMapper.selectList(any()))
                .thenReturn(List.of());

        String json = JsonMapper.builder().build()
                .writeValueAsString(
                        fixture.service.listRemaining(7L));

        assertThat(json)
                .doesNotContain("\"contextId\"")
                .doesNotContain("\"executionKey\"");
    }

    @Test
    void clearingConversationPlansAlsoDeletesRuntimeChildScenes() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = activeConversation(
                GroupChatConstant.MODE_TRPG, 12L);
        when(fixture.planMapper.selectList(any())).thenReturn(List.of(
                plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE, null),
                plan(12L, GroupChatConstant.PLAN_SOURCE_SCENE, null)
                        .setParentPlanId(10L)));

        fixture.service.clearConversationPlans(conversation);

        verify(fixture.runtimeChildSceneMapper).delete(any());
        assertThat(conversation.getActiveReplyPlanId()).isNull();
        verify(fixture.conversationMapper).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void trpgPlanStructureAcceptsImplicitKpIdentityWithNullActorId() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = activeConversation(
                GroupChatConstant.MODE_TRPG, null);

        assertThatCode(() -> fixture.service.validateStructure(
                conversation,
                sceneRequest(planActor(
                        GroupChatConstant.ACTOR_KP, null))))
                .doesNotThrowAnyException();

    }

    @Test
    void normalChatAndNonNullKpIdsAreRejected() {
        Fixture fixture = new Fixture();
        GroupConversation normalChat = activeConversation(
                GroupChatConstant.MODE_CHAT, null);

        assertThatThrownBy(() -> fixture.service.validateStructure(
                normalChat,
                userRequest(planActor(
                        GroupChatConstant.ACTOR_KP, null))))
                .hasMessageContaining("TRPG");

        GroupConversation trpg = activeConversation(
                GroupChatConstant.MODE_TRPG, null);
        assertThatThrownBy(() -> fixture.service.validateStructure(
                trpg,
                sceneRequest(planActor(
                        GroupChatConstant.ACTOR_KP, 9L))))
                .hasMessageContaining("KP");
    }

    @Test
    void publicReplaceCannotMutateTrpgSceneLifecycle() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireActive(7L))
                .thenReturn(activeConversation(
                        GroupChatConstant.MODE_TRPG, null));

        assertThatThrownBy(() -> fixture.service.replace(
                7L, sceneRequest(
                        planActor(GroupChatConstant.ACTOR_KP, null))))
                .hasMessageContaining("TRPG")
                .hasMessageContaining("生命周期");
    }

    @Test
    void publicFinishCannotMutateTrpgSceneLifecycle() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = activeConversation(
                GroupChatConstant.MODE_TRPG, 10L);
        when(fixture.conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(fixture.planMapper.selectById(10L)).thenReturn(
                plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE, null));

        assertThatThrownBy(() -> fixture.service.finishActive(7L))
                .hasMessageContaining("TRPG")
                .hasMessageContaining("生命周期");
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(10L);
    }

    @Test
    void internalCombatStartKeepsScenePlanAndBindsSubjectCard() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = activeConversation(
                GroupChatConstant.MODE_TRPG, 10L);
        GroupReplyPlan exploration = plan(
                10L, GroupChatConstant.PLAN_SOURCE_SCENE, null)
                .setContextId(100L);
        when(fixture.planMapper.selectById(10L))
                .thenReturn(exploration);
        doAnswer(invocation -> {
            ((GroupReplyPlan) invocation.getArgument(0)).setId(20L);
            return 1;
        }).when(fixture.planMapper).insert(any(GroupReplyPlan.class));
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(
                new GroupReplyPlanItem()
                        .setId(21L)
                        .setPlanId(20L)
                        .setItemOrder(1)
                        .setActorType(GroupChatConstant.ACTOR_KP)
                        .setSubjectCharacterId(71L)
                        .setSubjectCharacterName("食尸鬼")));

        var result = fixture.service.startCombatUnderLock(
                conversation, 200L, 1, List.of(
                        new GroupReplyPlanService.CombatPlanItem(
                                GroupChatConstant.ACTOR_KP,
                                null, 71L, "食尸鬼", 1)));

        assertThat(result.getResumePlanId()).isEqualTo(10L);
        assertThat(result.getContextId()).isEqualTo(200L);
        assertThat(result.getExecutionKey()).isEqualTo("combat:round:1");
        assertThat(result.getDisplayName()).isEqualTo("战斗第1轮");
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst()
                .getSubjectCharacterName()).isEqualTo("食尸鬼");
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(20L);
        verify(fixture.itemMapper).insert(
                org.mockito.ArgumentMatchers.argThat(
                        (GroupReplyPlanItem item) ->
                                item.getSubjectCharacterId().equals(71L)
                                        && "食尸鬼".equals(
                                        item.getSubjectCharacterName())
                                        && item.getActorId() == null
                                        && GroupChatConstant.ACTOR_KP.equals(
                                        item.getActorType())));
        verify(fixture.planMapper, never()).deleteById(10L);
    }

    @Test
    void finishingCombatRestoresExplorationPlan() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(20L);
        GroupReplyPlan combat = new GroupReplyPlan()
                .setId(20L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_COMBAT)
                .setContextId(200L)
                .setResumePlanId(10L);
        GroupReplyPlan exploration = new GroupReplyPlan()
                .setId(10L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(100L)
                .setExecutionKey("scene:100")
                .setDisplayName("地下室");
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.planMapper.selectById(20L)).thenReturn(combat);
        when(fixture.planMapper.selectById(10L)).thenReturn(exploration);
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(new GroupReplyPlanItem()
                .setId(11L)
                .setPlanId(10L)
                .setItemOrder(2)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(9L)));

        var result = fixture.service.finishActiveUnderLock(conversation);

        assertThat(result.getId()).isEqualTo(10L);
        assertThat(result.getExecutionKey()).isEqualTo("scene:100");
        assertThat(result.getDisplayName()).isEqualTo("地下室");
        assertThat(result.getItems().getFirst())
                .extracting(
                        com.me.galchat.domain.vo.GroupReplyPlanVO.Item::getId,
                        com.me.galchat.domain.vo.GroupReplyPlanVO.Item::getOrder,
                        com.me.galchat.domain.vo.GroupReplyPlanVO.Item::getActorType,
                        com.me.galchat.domain.vo.GroupReplyPlanVO.Item::getActorId)
                .containsExactly(11L, 2, GroupChatConstant.ACTOR_CHARACTER, 9L);
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(10L);
        verify(fixture.planMapper).deleteById(20L);
    }

    @Test
    void currentPlanUsesPlanMetadataForEveryItem() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(10L);
        GroupReplyPlan scene = new GroupReplyPlan()
                .setId(10L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(100L)
                .setExecutionKey("scene:100")
                .setDisplayName("地下室");
        GroupReplyPlanItem current = new GroupReplyPlanItem()
                .setId(11L)
                .setPlanId(10L)
                .setItemOrder(1)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(9L);
        GroupReplyPlanItem future = new GroupReplyPlanItem()
                .setId(12L)
                .setPlanId(10L)
                .setItemOrder(2)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(8L);
        when(fixture.planMapper.selectById(10L)).thenReturn(scene);
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(current, future));

        GroupReplyPlanSelection first = fixture.service.currentPlanForExecution(conversation);
        GroupReplyPlanSelection second = fixture.service.currentPlanForExecution(conversation);

        assertThat(first.items()).extracting(GroupReplyPlanItem::getId)
                .containsExactly(11L, 12L);
        assertThat(second.items()).extracting(GroupReplyPlanItem::getId)
                .containsExactly(11L, 12L);
        assertThat(first.source()).isEqualTo(GroupChatConstant.PLAN_SOURCE_SCENE);
        assertThat(first.contextId()).isEqualTo(100L);
        assertThat(first.executionKey()).isEqualTo("scene:100");
        assertThat(first.displayName()).isEqualTo("地下室");
        verify(fixture.itemMapper, never()).updateById(any(GroupReplyPlanItem.class));
    }

    @Test
    void finishingSceneLeavesConversationWithoutPlan() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = activeConversation(GroupChatConstant.MODE_TRPG, 10L);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.planMapper.selectById(10L))
                .thenReturn(plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE, null));
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of());

        var result = fixture.service.finishActiveUnderLock(
                conversation);

        assertThat(result).isNull();
        assertThat(conversation.getActiveReplyPlanId()).isNull();
        verify(fixture.planMapper).deleteById(10L);
        verify(fixture.conversationMapper).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void finishingSceneActivatesItsNextScenePlan() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = activeConversation(GroupChatConstant.MODE_TRPG, 10L);
        GroupReplyPlan current = plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE, null)
                .setNextPlanId(11L);
        GroupReplyPlan next = plan(11L, GroupChatConstant.PLAN_SOURCE_SCENE, null)
                .setContextId(101L);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.planMapper.selectById(10L)).thenReturn(current);
        when(fixture.planMapper.selectById(11L)).thenReturn(next);
        when(fixture.itemMapper.selectList(any())).thenReturn(
                List.of(),
                List.of(item(12L, 11L, 8L)));

        var result = fixture.service.finishActiveUnderLock(
                conversation);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(11L);
        assertThat(result.getContextId()).isEqualTo(101L);
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(11L);
        verify(fixture.planMapper).deleteById(10L);
        verify(fixture.planMapper, never()).deleteById(11L);
    }

    @Test
    void finishingSceneRejectsMissingNextPlanBeforeDeletingCurrent() {
        Fixture fixture = new Fixture();
        GroupConversation conversation =
                activeConversation(GroupChatConstant.MODE_TRPG, 10L);
        GroupReplyPlan current =
                plan(10L, GroupChatConstant.PLAN_SOURCE_SCENE, null)
                        .setNextPlanId(11L);
        when(fixture.conversationService.requireActive(7L))
                .thenReturn(conversation);
        when(fixture.planMapper.selectById(10L))
                .thenReturn(current);
        when(fixture.planMapper.selectById(11L))
                .thenReturn(null);

        assertThatThrownBy(() -> fixture.service
                .finishActiveUnderLock(conversation))
                .isInstanceOf(
                        com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("下一场景");
        verify(fixture.planMapper, never()).deleteById(10L);
    }

    @Test
    void rejectsPlanSourceThatDoesNotMatchConversationMode() {
        Fixture fixture = new Fixture();
        GroupConversation trpg = activeConversation(
                GroupChatConstant.MODE_TRPG, null);

        assertThatThrownBy(() -> fixture.service.validateStructure(
                trpg, userRequest()))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("USER");

        GroupConversation normalChat = activeConversation(
                GroupChatConstant.MODE_CHAT, null);
        assertThatThrownBy(() -> fixture.service.validateStructure(
                normalChat, combatRequest()))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("普通群聊");
    }

    @Test
    void sceneAndCombatPlansRequireContextId() {
        Fixture fixture = new Fixture();
        GroupConversation trpg = activeConversation(
                GroupChatConstant.MODE_TRPG, null);
        GroupReplyPlanDTO request = sceneRequest(
                planActor(GroupChatConstant.ACTOR_KP, null));
        request.setContextId(null);

        assertThatThrownBy(() -> fixture.service.validateStructure(
                trpg, request))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("contextId");
    }

    @Test
    void rejectsDisabledPlanMember() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireActive(7L))
                .thenReturn(activeConversation(GroupChatConstant.MODE_CHAT, null));
        org.mockito.Mockito.doThrow(new com.me.galchat.exception.UserRequestException("回复角色已被禁用"))
                .when(fixture.conversationService)
                .checkReplyMember(7L, GroupChatConstant.ACTOR_CHARACTER, 9L, false);

        assertThatThrownBy(() -> fixture.service.replace(7L, userRequest()))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("禁用");
    }

    @Test
    void rejectsPlanLargerThanReplyStepLimit() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireActive(7L))
                .thenReturn(activeConversation(GroupChatConstant.MODE_CHAT, null));
        GroupReplyPlanDTO request = new GroupReplyPlanDTO();
        request.setSource(GroupChatConstant.PLAN_SOURCE_USER);
        request.setExecutionKey("default");
        request.setDisplayName("群聊");
        request.setItems(IntStream.rangeClosed(1, 13)
                .mapToObj(index -> {
                    GroupReplyPlanDTO.Item item = new GroupReplyPlanDTO.Item();
                    item.setActorId((long) index);
                    return item;
                })
                .toList());

        assertThatThrownBy(() -> fixture.service.replace(7L, request))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("12");
    }

    @Test
    void replaceRejectsNonTerminalTurnBeforeMutatingPlan() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireActive(7L))
                .thenReturn(activeConversation(GroupChatConstant.MODE_CHAT, null));
        org.mockito.Mockito.doThrow(new com.me.galchat.exception.UserRequestException("存在未完成的群聊轮次"))
                .when(fixture.recoveryService).assertConversationHasNoNonTerminalTurns(7L);

        assertThatThrownBy(() -> fixture.service.replace(7L, userRequest()))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("未完成");
        verify(fixture.planMapper, never()).insert(any(GroupReplyPlan.class));
    }

    @Test
    void replaceReadsConversationStateAfterAcquiringLock() {
        Fixture fixture = new Fixture();
        when(fixture.conversationService.requireActive(7L))
                .thenThrow(new com.me.galchat.exception.UserRequestException("群聊会话已结束"));

        assertThatThrownBy(() -> fixture.service.replace(7L, userRequest()))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class)
                .hasMessageContaining("已结束");

        var ordered = inOrder(fixture.lockService, fixture.conversationService);
        ordered.verify(fixture.conversationService).requireAuthorized(7L);
        ordered.verify(fixture.lockService).tryLock(7L);
        ordered.verify(fixture.conversationService).requireActive(7L);
        verify(fixture.planMapper, never()).insert(any(GroupReplyPlan.class));
    }

    @Test
    void finishingNormalPlanRestoresEnabledMemberOrder() {
        Fixture fixture = new Fixture();
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setMode(GroupChatConstant.MODE_CHAT)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(10L);
        GroupReplyPlan userPlan = new GroupReplyPlan()
                .setId(10L)
                .setConversationId(7L)
                .setSource(GroupChatConstant.PLAN_SOURCE_USER);
        when(fixture.conversationService.requireActive(7L)).thenReturn(conversation);
        when(fixture.conversationService.listMembers(7L)).thenReturn(List.of(
                member(9L, true), member(8L, false), member(7L, true)));
        when(fixture.planMapper.selectById(10L)).thenReturn(userPlan);
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            ((GroupReplyPlan) invocation.getArgument(0)).setId(30L);
            return 1;
        }).when(fixture.planMapper).insert(any(GroupReplyPlan.class));
        var itemCaptor = org.mockito.ArgumentCaptor.forClass(GroupReplyPlanItem.class);

        var result = fixture.service.finishActive(7L);

        assertThat(result.getSource()).isEqualTo(GroupChatConstant.PLAN_SOURCE_USER);
        assertThat(result.getExecutionKey()).isEqualTo("default");
        assertThat(result.getDisplayName()).isEqualTo("群聊");
        assertThat(conversation.getActiveReplyPlanId()).isEqualTo(30L);
        verify(fixture.itemMapper, org.mockito.Mockito.times(2)).insert(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues()).extracting(GroupReplyPlanItem::getActorId)
                .containsExactly(9L, 7L);
    }

    private GroupReplyPlanDTO combatRequest() {
        GroupReplyPlanDTO.Item actor = new GroupReplyPlanDTO.Item();
        actor.setActorId(9L);
        GroupReplyPlanDTO request = new GroupReplyPlanDTO();
        request.setSource(GroupChatConstant.PLAN_SOURCE_COMBAT);
        request.setContextId(200L);
        request.setExecutionKey("combat:round:1");
        request.setDisplayName("战斗第1轮");
        request.setItems(List.of(actor));
        return request;
    }

    private GroupReplyPlanDTO userRequest() {
        GroupReplyPlanDTO request = combatRequest();
        request.setSource(GroupChatConstant.PLAN_SOURCE_USER);
        request.setContextId(null);
        request.setExecutionKey("default");
        request.setDisplayName("群聊");
        return request;
    }

    private GroupReplyPlanDTO userRequest(GroupReplyPlanDTO.Item item) {
        GroupReplyPlanDTO request = userRequest();
        request.setItems(List.of(item));
        return request;
    }

    private GroupReplyPlanDTO sceneRequest(GroupReplyPlanDTO.Item item) {
        GroupReplyPlanDTO request = combatRequest();
        request.setSource(GroupChatConstant.PLAN_SOURCE_SCENE);
        request.setContextId(100L);
        request.setExecutionKey("scene:100");
        request.setDisplayName("地下室");
        request.setItems(List.of(item));
        return request;
    }

    private GroupReplyPlanDTO.Item planActor(String actorType, Long actorId) {
        GroupReplyPlanDTO.Item item = new GroupReplyPlanDTO.Item();
        item.setActorType(actorType);
        item.setActorId(actorId);
        return item;
    }

    private GroupConversation activeConversation(String mode, Long activePlanId) {
        return new GroupConversation()
                .setId(7L)
                .setMode(mode)
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setActiveReplyPlanId(activePlanId);
    }

    private GroupReplyPlan plan(Long id, String source, Long resumePlanId) {
        return new GroupReplyPlan()
                .setId(id)
                .setConversationId(7L)
                .setSource(source)
                .setContextId(GroupChatConstant.PLAN_SOURCE_USER.equals(source) ? null : 100L)
                .setExecutionKey(GroupChatConstant.PLAN_SOURCE_USER.equals(source)
                        ? "default" : "scene:100")
                .setDisplayName(GroupChatConstant.PLAN_SOURCE_USER.equals(source)
                        ? "群聊" : "地下室")
                .setResumePlanId(resumePlanId);
    }

    private GroupReplyPlanItem item(Long id, Long planId, Long actorId) {
        return new GroupReplyPlanItem()
                .setId(id)
                .setPlanId(planId)
                .setItemOrder(1)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId);
    }

    private GroupChatMember member(Long actorId, boolean enabled) {
        return new GroupChatMember()
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId)
                .setEnabled(enabled);
    }

    private static class Fixture {
        private final GroupConversationService conversationService = mock(GroupConversationService.class);
        private final GroupConversationLockService lockService = mock(GroupConversationLockService.class);
        private final GroupConversationMapper conversationMapper = mock(GroupConversationMapper.class);
        private final GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        private final GroupReplyPlanItemMapper itemMapper = mock(GroupReplyPlanItemMapper.class);
        private final TrpgRuntimeChildSceneMapper runtimeChildSceneMapper =
                mock(TrpgRuntimeChildSceneMapper.class);
        private final GroupTurnRecoveryService recoveryService = mock(GroupTurnRecoveryService.class);
        private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        private final GroupReplyPlanService service = new GroupReplyPlanService(conversationService, lockService,
                conversationMapper, planMapper, itemMapper,
                runtimeChildSceneMapper, recoveryService,
                transactionTemplate, mock(TrpgParticipantService.class));

        private Fixture() {
            when(lockService.tryLock(7L)).thenReturn(
                    new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });
        }
    }
}
