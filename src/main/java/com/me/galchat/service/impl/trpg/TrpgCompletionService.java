package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgCompletionModels.*;
import com.me.galchat.domain.dto.TrpgEpilogueModels;
import com.me.galchat.domain.po.*;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.TrpgCompletionMapper;
import com.me.galchat.service.impl.group.GroupConversationLifecycleService;
import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.group.GroupActorRuntimeService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TrpgCompletionService {
    private final TrpgCompletionMapper mapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupConversationService conversations;
    private final TrpgCompletionMaterialsService materialsService;
    private final TrpgEpilogueService epilogues;
    private final TrpgCompletionGenerator generator;
    private final GroupConversationLifecycleService lifecycle;
    private final TransactionTemplate transactions;
    private final com.me.galchat.mapper.GroupChatReplyStepMapper steps;
    private final GroupActorRuntimeService actorRuntimeService;
    private final ChatClient kpDefaultClient;

    public TrpgCompletionService(TrpgCompletionMapper mapper, GroupChatTurnMapper turnMapper,
            GroupConversationService conversations, TrpgCompletionMaterialsService materialsService,
            TrpgEpilogueService epilogues, TrpgCompletionGenerator generator,
            GroupConversationLifecycleService lifecycle, TransactionTemplate transactions,
            com.me.galchat.mapper.GroupChatReplyStepMapper steps, GroupActorRuntimeService actorRuntimeService,
            @Qualifier("trpgGroupChatClient") ChatClient kpDefaultClient) {
        this.mapper = mapper;
        this.turnMapper = turnMapper;
        this.conversations = conversations;
        this.materialsService = materialsService;
        this.epilogues = epilogues;
        this.generator = generator;
        this.lifecycle = lifecycle;
        this.transactions = transactions;
        this.steps = steps;
        this.actorRuntimeService = actorRuntimeService;
        this.kpDefaultClient = kpDefaultClient;
    }


    public Report get(Long conversationId) {
        var conversation = conversations.requireAuthorized(conversationId);
        return report(mapper.selectById(conversationId), conversation);
    }

    /** One action: prepare, generate concurrently, then commit everything together. */
    public void executeUnderLock(GroupConversation conversation, GroupChatTurn turn, GroupChatReplyStep step) {
        TrpgCompletion saved = mapper.selectById(conversation.getId());
        if (saved == null || !GroupChatConstant.TURN_SOURCE_SUMMARY.equals(turn.getPlanSource())
                || !GroupChatConstant.ACTION_TRPG_SUMMARY.equals(step.getActionType())
                || !Objects.equals(step.getTurnId(), turn.getId())
                || !Objects.equals(turn.getConversationId(), conversation.getId())
                || !GroupChatConstant.STATUS_ACTIVE.equals(conversation.getStatus())
                || conversation.getActiveReplyPlanId() != null) {
            throw new UserRequestException("当前不是待生成总结的行动轮");
        }
        GroupChatTurn source = turnMapper.selectById(saved.getTurnId());
        if (source == null || !GroupChatConstant.STATUS_COMPLETED.equals(source.getStatus())) {
            throw new UserRequestException("请先完成最后的场景");
        }
        // Resolve and snapshot the KP runtime on this step before launching concurrent requests.
        ChatClient selectedClient = actorRuntimeService.chatClient(conversation, step, kpDefaultClient);
        Materials materials = materialsService.capture(conversation, saved.getTurnId());
        Data completed;
        // Wait for both branches before releasing the conversation lock, including on failure.
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var endings = executor.submit(() -> epilogues.generate(selectedClient, conversation, materials));
            var overview = executor.submit(() -> generator.generate(selectedClient, materials));
            completed = new Data(materials, endings.get(), overview.get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("总结生成被中断，请重试", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("总结生成失败，请重试", exception.getCause());
        }
        transactions.executeWithoutResult(status -> {
            epilogues.persist(conversation, completed.epilogues(), turn.getId(), step.getId());
            step.setStatus(GroupChatConstant.STATUS_COMPLETED).setUpdatedAt(LocalDateTime.now());
            steps.updateById(step);
            turn.setStatus(GroupChatConstant.STATUS_COMPLETED).setUpdatedAt(LocalDateTime.now());
            turnMapper.updateById(turn);
            lifecycle.closeWithCompletionUnderLock(conversation, completed.materials(), completed.overview().summary());
            saved.setData(completed);
            mapper.updateById(saved);
        });
    }

    private Report report(TrpgCompletion saved, GroupConversation conversation) {
        if (saved == null) return null;
        if (saved.getData() == null) {
            return new Report(conversation.getActiveReplyPlanId() == null ? "pending" : "requested", null, null, null, null, null, 0, List.of(), List.of(), List.of(), null);
        }
        Data data = saved.getData();
        Materials material = data.materials();
        Map<Long, TrpgEpilogueModels.Entry> endings = data.epilogues().stream()
                .collect(Collectors.toMap(TrpgEpilogueModels.Entry::characterId, Function.identity()));
        List<Person> people = material.investigators().stream().map(person -> {
            var subject = person.subject();
            var epilogue = endings.get(subject.characterId());
            return new Person(subject.characterId(), subject.investigatorName(), subject.occupation(), person.image(),
                    person.player(), subject.dead(), subject.dying(), subject.unconscious(), subject.majorWound(),
                    subject.temporaryInsanity(), person.initialHp(), subject.hpCurrent(), person.initialSan(),
                    subject.sanCurrent(), epilogue.lead(), epilogue.content());
        }).toList();
        List<Journey> journey = data.overview().journey().stream().map(chapter ->
                new Journey(chapter.title(), chapter.excerpt(), material.sources().get(chapter.sourceIndex()).text())).toList();
        List<Combat> combats = material.combats();
        if (combats != null && combats.stream().anyMatch(combat -> !org.springframework.util.StringUtils.hasText(combat.sceneName()))) {
            combats = materialsService.fillMissingCombatSceneNames(conversation.getId(), combats);
        }
        return new Report("ready", conversation.getClosedAt(), conversation.getClosedAt(), material.title(),
                material.coverUrl(), data.overview().ending(), material.turnCount(), journey, people, material.rolls(), combats);
    }
}
