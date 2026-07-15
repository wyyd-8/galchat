package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupChatReplyStep;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.domain.vo.GroupChatMessageVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.context.GroupContextStrategyRouter;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class GroupChatService {

    private final ChatClient chatClient;
    private final GroupConversationService conversationService;
    private final GroupConversationLockService lockService;
    private final GroupReplyPlanService replyPlanService;
    private final GroupContextAssembler contextAssembler;
    private final GroupContextStrategyRouter contextStrategyRouter;
    private final GroupChatMessageMapper messageMapper;
    private final GroupChatTurnMapper turnMapper;
    private final GroupChatReplyStepMapper stepMapper;
    private final TransactionTemplate transactionTemplate;

    public GroupChatService(@Qualifier("groupThinkingChatClient") ChatClient chatClient,
                            GroupConversationService conversationService,
                            GroupConversationLockService lockService,
                            GroupReplyPlanService replyPlanService,
                            GroupContextAssembler contextAssembler,
                            GroupContextStrategyRouter contextStrategyRouter,
                            GroupChatMessageMapper messageMapper,
                            GroupChatTurnMapper turnMapper,
                            GroupChatReplyStepMapper stepMapper,
                            TransactionTemplate transactionTemplate) {
        this.chatClient = chatClient;
        this.conversationService = conversationService;
        this.lockService = lockService;
        this.replyPlanService = replyPlanService;
        this.contextAssembler = contextAssembler;
        this.contextStrategyRouter = contextStrategyRouter;
        this.messageMapper = messageMapper;
        this.turnMapper = turnMapper;
        this.stepMapper = stepMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public Flux<GroupChatEvent> chat(Long conversationId, GroupChatRequestDTO request) {
        return Flux.defer(() -> {
            validateRequest(request);
            GroupConversation conversation = conversationService.requireActive(conversationId);
            GroupConversationLockService.OwnedLock lock = lockService.tryLock(conversationId);
            if (lock == null) {
                return Flux.error(new UserRequestException("当前群聊正在生成回复，请稍后再试"));
            }

            try {
                PreparedTurn prepared = transactionTemplate.execute(status -> prepareTurn(conversation, request));
                if (prepared == null) {
                    throw new UserRequestException("创建群聊轮次失败");
                }
                Flux<GroupChatEvent> accepted = Flux.just(GroupChatEvent.builder()
                        .eventType(GroupChatConstant.EVENT_TURN_ACCEPTED)
                        .conversationId(conversationId)
                        .turnId(prepared.turn().getId())
                        .messageId(prepared.userMessage().getId())
                        .sequence(prepared.userMessage().getSequenceNo())
                        .build());
                Flux<GroupChatEvent> replies = Flux.fromIterable(prepared.replies())
                        .concatMap(reply -> executeStep(conversation, prepared.turn(), reply));
                Flux<GroupChatEvent> completed = Flux.defer(() -> {
                    completeTurn(prepared.turn());
                    return Flux.just(GroupChatEvent.builder()
                            .eventType(GroupChatConstant.EVENT_TURN_COMPLETED)
                            .conversationId(conversationId)
                            .turnId(prepared.turn().getId())
                            .build());
                });
                return Flux.concat(accepted, replies, completed)
                        .doOnError(error -> failTurn(prepared.turn(), error))
                        .onErrorResume(error -> Flux.just(GroupChatEvent.builder()
                                .eventType(GroupChatConstant.EVENT_REPLY_FAILED)
                                .conversationId(conversationId)
                                .turnId(prepared.turn().getId())
                                .error(error.getMessage())
                                .build()))
                        .doFinally(signal -> {
                            if (signal == SignalType.CANCEL) {
                                cancelTurn(prepared.turn());
                            }
                            lockService.unlock(lock);
                        });
            } catch (RuntimeException e) {
                lockService.unlock(lock);
                return Flux.error(e);
            }
        });
    }

    public List<GroupChatMessageVO> listHistory(Long conversationId, Long beforeId, Integer requestedSize) {
        GroupConversation conversation = conversationService.requireAuthorized(conversationId);
        int size = requestedSize == null ? GroupChatConstant.DEFAULT_HISTORY_PAGE_SIZE : requestedSize;
        if (size <= 0 || size > 200) {
            throw new UserRequestException("查询条数必须在1到200之间");
        }
        List<GroupChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<GroupChatMessage>()
                .eq(GroupChatMessage::getConversationId, conversationId)
                .lt(beforeId != null, GroupChatMessage::getId, beforeId)
                .orderByDesc(GroupChatMessage::getId)
                .last("limit " + size));
        Collections.reverse(messages);
        if (messages.isEmpty()) {
            return List.of();
        }

        return messages.stream().map(message -> new GroupChatMessageVO(
                message.getId(), message.getConversationId(), message.getTurnId(), message.getReplyStepId(),
                message.getSpeakerType(), message.getSpeakerId(), speakerName(conversation, message),
                message.getMessageKind(), message.getContent(),
                message.getSequenceNo(), message.getStatus(), message.getCreatedAt())).toList();
    }

    private PreparedTurn prepareTurn(GroupConversation conversation, GroupChatRequestDTO request) {
        if (StringUtils.hasText(request.getClientRequestId())) {
            Long duplicateCount = turnMapper.selectCount(new LambdaQueryWrapper<GroupChatTurn>()
                    .eq(GroupChatTurn::getConversationId, conversation.getId())
                    .eq(GroupChatTurn::getClientRequestId, request.getClientRequestId()));
            if (duplicateCount != null && duplicateCount > 0) {
                throw new UserRequestException("clientRequestId已处理，请勿重复提交");
            }
        }

        List<GroupReplyPlanItem> plannedItems = replyPlanService.pendingItemsForExecution(conversation);
        if (plannedItems.size() > GroupChatConstant.MAX_REPLY_STEPS) {
            throw new UserRequestException("单次回复人物数量不能超过" + GroupChatConstant.MAX_REPLY_STEPS);
        }
        for (GroupReplyPlanItem item : plannedItems) {
            conversationService.checkReplyMember(conversation.getId(), item.getActorType(), item.getActorId(), false);
        }

        LocalDateTime now = LocalDateTime.now();
        GroupChatTurn turn = new GroupChatTurn()
                .setConversationId(conversation.getId())
                .setClientRequestId(StringUtils.hasText(request.getClientRequestId())
                        ? request.getClientRequestId().trim() : null)
                .setPolicy(replyPlanService.activeSource(conversation).toLowerCase())
                .setStatus(GroupChatConstant.STATUS_PENDING)
                .setRevision(0)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        turnMapper.insert(turn);

        GroupChatMessage userMessage = new GroupChatMessage()
                .setConversationId(conversation.getId())
                .setTurnId(turn.getId())
                .setSpeakerType(GroupChatConstant.ACTOR_USER)
                .setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                .setVisibility("public")
                .setContent(request.getContent().trim())
                .setSequenceNo(conversationService.nextSequence(conversation.getId()))
                .setStatus(GroupChatConstant.STATUS_COMPLETED)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messageMapper.insert(userMessage);
        turn.setTriggerMessageId(userMessage.getId()).setStatus(GroupChatConstant.STATUS_RUNNING);
        turnMapper.updateById(turn);

        List<PreparedReply> replies = new ArrayList<>();
        for (int i = 0; i < plannedItems.size(); i++) {
            GroupReplyPlanItem item = plannedItems.get(i);
            GroupChatReplyStep step = new GroupChatReplyStep()
                    .setTurnId(turn.getId())
                    .setStepNo(i + 1)
                    .setSpeakerType(item.getActorType())
                    .setSpeakerId(item.getActorId())
                    .setForceReply(false)
                    .setStatus(GroupChatConstant.STATUS_PENDING)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            stepMapper.insert(step);
            replies.add(new PreparedReply(item, step));
        }
        return new PreparedTurn(turn, userMessage, replies);
    }

    private Flux<GroupChatEvent> executeStep(GroupConversation conversation, GroupChatTurn turn,
                                             PreparedReply reply) {
        return Flux.defer(() -> {
            GroupReplyPlanItem planItem = reply.planItem();
            GroupChatReplyStep step = reply.step();
            replyPlanService.markRunning(planItem);
            step.setStatus(GroupChatConstant.STATUS_RUNNING).setUpdatedAt(LocalDateTime.now());
            stepMapper.updateById(step);
            GroupChatMessage outputMessage = createStreamingMessage(conversation, turn, step);
            String speakerName = contextAssembler.characterName(conversation.getUserWorldId(), step.getSpeakerId());
            GroupChatEvent.Speaker speaker = GroupChatEvent.Speaker.builder()
                    .type(step.getSpeakerType()).id(step.getSpeakerId()).name(speakerName).build();
            GenerationAccumulator accumulator = new GenerationAccumulator();
            AtomicBoolean finalized = new AtomicBoolean(false);

            contextStrategyRouter.compactIfNeeded(conversation);
            Prompt prompt = new Prompt(contextAssembler.assemble(conversation, step.getSpeakerId()));
            Flux<GroupChatEvent> started = Flux.just(baseEvent(GroupChatConstant.EVENT_REPLY_STARTED,
                    conversation, turn, step, outputMessage, speaker).build());
            Flux<GroupChatEvent> deltas = chatClient.prompt(prompt).stream().chatResponse()
                    .flatMapIterable(response -> toEvents(response, conversation, turn, step, outputMessage,
                            speaker, accumulator));
            Flux<GroupChatEvent> finished = Flux.defer(() -> {
                finalizeCompletedStep(planItem, step, outputMessage, accumulator, finalized);
                return Flux.just(baseEvent(GroupChatConstant.EVENT_MESSAGE_COMPLETED,
                        conversation, turn, step, outputMessage, speaker)
                        .content(accumulator.content.toString())
                        .build());
            });
            return Flux.concat(started, deltas, finished)
                    .doOnError(error -> finalizeFailedStep(planItem, step, outputMessage, accumulator, finalized, error))
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL && finalized.compareAndSet(false, true)) {
                            transactionTemplate.executeWithoutResult(status -> {
                                persistOutput(outputMessage, accumulator, GroupChatConstant.STATUS_CANCELLED);
                                updateStepStatus(step, GroupChatConstant.STATUS_CANCELLED, "客户端取消生成");
                                replyPlanService.resetPending(planItem);
                            });
                        }
                    });
        });
    }

    private GroupChatMessage createStreamingMessage(GroupConversation conversation, GroupChatTurn turn,
                                                    GroupChatReplyStep step) {
        LocalDateTime now = LocalDateTime.now();
        GroupChatMessage message = new GroupChatMessage()
                .setConversationId(conversation.getId())
                .setTurnId(turn.getId())
                .setReplyStepId(step.getId())
                .setSpeakerType(step.getSpeakerType())
                .setSpeakerId(step.getSpeakerId())
                .setMessageKind(GroupChatConstant.MESSAGE_DIALOGUE)
                .setVisibility("public")
                .setContent("")
                .setSequenceNo(conversationService.nextSequence(conversation.getId()))
                .setStatus(GroupChatConstant.STATUS_STREAMING)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messageMapper.insert(message);
        step.setOutputMessageId(message.getId());
        stepMapper.updateById(step);
        return message;
    }

    private List<GroupChatEvent> toEvents(ChatResponse response, GroupConversation conversation,
                                          GroupChatTurn turn, GroupChatReplyStep step,
                                          GroupChatMessage message, GroupChatEvent.Speaker speaker,
                                          GenerationAccumulator accumulator) {
        if (response == null || response.getResults() == null) {
            return List.of();
        }
        List<GroupChatEvent> events = new ArrayList<>();
        for (Generation generation : response.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output instanceof DeepSeekAssistantMessage deepSeek) {
                String reasoning = deepSeek.getReasoningContent();
                if (StringUtils.hasText(reasoning)) {
                    accumulator.reasoning.append(reasoning);
                    events.add(baseEvent(GroupChatConstant.EVENT_REASONING_DELTA,
                            conversation, turn, step, message, speaker).delta(reasoning).build());
                }
            }
            String content = output.getText();
            if (StringUtils.hasText(content)) {
                accumulator.content.append(content);
                events.add(baseEvent(GroupChatConstant.EVENT_MESSAGE_DELTA,
                        conversation, turn, step, message, speaker).delta(content).build());
            }
        }
        return events;
    }

    private void finalizeCompletedStep(GroupReplyPlanItem planItem, GroupChatReplyStep step, GroupChatMessage message,
                                       GenerationAccumulator accumulator, AtomicBoolean finalized) {
        if (!finalized.compareAndSet(false, true)) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            persistOutput(message, accumulator, GroupChatConstant.STATUS_COMPLETED);
            updateStepStatus(step, GroupChatConstant.STATUS_COMPLETED, null);
            replyPlanService.markCompleted(planItem);
        });
    }

    private void finalizeFailedStep(GroupReplyPlanItem planItem, GroupChatReplyStep step, GroupChatMessage message,
                                    GenerationAccumulator accumulator, AtomicBoolean finalized, Throwable error) {
        if (!finalized.compareAndSet(false, true)) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            persistOutput(message, accumulator, GroupChatConstant.STATUS_FAILED);
            updateStepStatus(step, GroupChatConstant.STATUS_FAILED, error == null ? "生成失败" : error.getMessage());
            replyPlanService.resetPending(planItem);
        });
    }

    private void persistOutput(GroupChatMessage message, GenerationAccumulator accumulator, String status) {
        message.setContent(accumulator.content.toString())
                .setStatus(status)
                .setUpdatedAt(LocalDateTime.now());
        messageMapper.updateById(message);
    }

    private void updateStepStatus(GroupChatReplyStep step, String status, String error) {
        step.setStatus(status).setErrorMessage(error).setUpdatedAt(LocalDateTime.now());
        stepMapper.updateById(step);
    }

    private void completeTurn(GroupChatTurn turn) {
        turn.setStatus(GroupChatConstant.STATUS_COMPLETED).setUpdatedAt(LocalDateTime.now());
        turnMapper.updateById(turn);
    }

    private void failTurn(GroupChatTurn turn, Throwable error) {
        turn.setStatus(GroupChatConstant.STATUS_FAILED).setUpdatedAt(LocalDateTime.now());
        turnMapper.updateById(turn);
    }

    private void cancelTurn(GroupChatTurn turn) {
        turn.setStatus(GroupChatConstant.STATUS_CANCELLED).setUpdatedAt(LocalDateTime.now());
        turnMapper.updateById(turn);
    }

    private GroupChatEvent.GroupChatEventBuilder baseEvent(String eventType, GroupConversation conversation,
                                                           GroupChatTurn turn, GroupChatReplyStep step,
                                                           GroupChatMessage message,
                                                           GroupChatEvent.Speaker speaker) {
        return GroupChatEvent.builder()
                .eventType(eventType)
                .conversationId(conversation.getId())
                .turnId(turn.getId())
                .replyStepId(step.getId())
                .messageId(message.getId())
                .sequence(message.getSequenceNo())
                .speaker(speaker);
    }

    private String speakerName(GroupConversation conversation, GroupChatMessage message) {
        if (GroupChatConstant.ACTOR_USER.equals(message.getSpeakerType())) {
            return "用户";
        }
        if (GroupChatConstant.ACTOR_CHARACTER.equals(message.getSpeakerType())) {
            return contextAssembler.characterName(conversation.getUserWorldId(), message.getSpeakerId());
        }
        return "旁白";
    }

    private void validateRequest(GroupChatRequestDTO request) {
        if (request == null || !StringUtils.hasText(request.getContent())) {
            throw new UserRequestException("群聊消息内容不能为空");
        }
        if (request.getClientRequestId() != null && request.getClientRequestId().length() > 100) {
            throw new UserRequestException("clientRequestId长度不能超过100");
        }
    }

    private record PreparedTurn(GroupChatTurn turn, GroupChatMessage userMessage,
                                List<PreparedReply> replies) {
    }

    private record PreparedReply(GroupReplyPlanItem planItem, GroupChatReplyStep step) {
    }

    private static class GenerationAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
    }
}
