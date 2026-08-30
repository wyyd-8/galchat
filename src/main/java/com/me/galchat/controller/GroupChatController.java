package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.dto.GroupConversationCreateDTO;
import com.me.galchat.domain.dto.GroupReplyPlanDTO;
import com.me.galchat.domain.dto.GroupEndExplorationDTO;
import com.me.galchat.domain.dto.GroupTurnContinueDTO;
import com.me.galchat.domain.dto.GroupSceneSelectionDTO;
import com.me.galchat.domain.dto.TrpgGameTimeUpdateDTO;
import com.me.galchat.domain.dto.TrpgInvestigatorInquiryDTO;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.service.impl.GroupChatService;
import com.me.galchat.service.impl.GroupGenerationStreamRegistry;
import com.me.galchat.service.impl.GenerationRequestContext;
import com.me.galchat.service.impl.GroupChatWithdrawalService;
import com.me.galchat.service.impl.GroupConversationService;
import com.me.galchat.service.impl.GroupConversationLifecycleService;
import com.me.galchat.service.impl.GroupReplyPlanService;
import com.me.galchat.service.impl.TrpgContextWindowService;
import com.me.galchat.service.impl.TrpgTurnExecutionService;
import com.me.galchat.service.impl.TrpgGameTimeService;
import com.me.galchat.utils.CurrentHolder;
import com.me.galchat.exception.UserRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/group-chat")
@RequiredArgsConstructor
public class GroupChatController {

    private final GroupConversationService conversationService;
    private final GroupConversationLifecycleService lifecycleService;
    private final GroupChatService groupChatService;
    private final GroupGenerationStreamRegistry generationStreamRegistry;
    private final GroupChatWithdrawalService withdrawalService;
    private final GroupReplyPlanService replyPlanService;
    private final TrpgContextWindowService contextWindowService;
    private final TrpgTurnExecutionService turnExecutionService;
    private final TrpgGameTimeService gameTimeService;

    @PostMapping("/conversations")
    public Result createConversation(@RequestBody GroupConversationCreateDTO dto) {
        return Result.success(conversationService.create(dto));
    }

    @GetMapping("/conversations")
    public Result listConversations(@RequestParam Long userWorldId,
                                    @RequestParam(required = false) String status) {
        return Result.success(conversationService.list(userWorldId, status));
    }

    @GetMapping("/conversations/{conversationId}")
    public Result getConversation(@PathVariable Long conversationId) {
        return Result.success(conversationService.get(conversationId));
    }

    @GetMapping("/conversations/{conversationId}/context-window")
    public Result getContextWindow(@PathVariable Long conversationId) {
        conversationService.requireAuthorized(conversationId);
        return Result.success(contextWindowService.get(conversationId));
    }

    @PutMapping("/conversations/{conversationId}/game-time")
    public Result updateGameTime(
            @PathVariable Long conversationId,
            @RequestBody TrpgGameTimeUpdateDTO request) {
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        return Result.success(gameTimeService.correct(
                Long.valueOf(userId), conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/close")
    public Result closeConversation(@PathVariable Long conversationId) {
        return Result.success(lifecycleService.close(conversationId));
    }

    @PostMapping(value = "/conversations/{conversationId}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> chat(@PathVariable Long conversationId,
                                     @RequestBody GroupChatRequestDTO request) {
        conversationService.requireAuthorized(conversationId);
        return generationStreamRegistry.start(
                conversationId,
                request == null ? null : request.getClientRequestId(),
                requestContext(
                        "send-group-message",
                        "/group-chat/conversations/" + conversationId
                                + "/messages",
                        request == null ? null
                                : request.getClientRequestId(),
                        "content", request == null ? null
                                : request.getContent()),
                groupChatService.chat(conversationId, request));
    }

    @GetMapping(
            value = "/conversations/{conversationId}/generations/{clientRequestId}",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> resumeGeneration(
            @PathVariable Long conversationId,
            @PathVariable String clientRequestId) {
        conversationService.requireAuthorized(conversationId);
        return generationStreamRegistry.resume(
                conversationId, clientRequestId);
    }

    @PostMapping(
            value = "/conversations/{conversationId}/turns/continue",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> continueTurn(
            @PathVariable Long conversationId,
            @RequestBody GroupTurnContinueDTO request) {
        conversationService.requireAuthorized(conversationId);
        return generationStreamRegistry.start(
                conversationId,
                request == null ? null : request.getClientRequestId(),
                requestContext(
                        "continue-trpg-turn",
                        "/group-chat/conversations/" + conversationId
                                + "/turns/continue",
                        request == null ? null
                                : request.getClientRequestId(),
                        null, null),
                turnExecutionService.continueTurn(
                        conversationId, request));
    }

    @PostMapping(
            value = "/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/retry",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> retryTurnStep(
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @PathVariable Long stepId,
            @RequestBody(required = false) GroupTurnContinueDTO request) {
        conversationService.requireAuthorized(conversationId);
        return generationStreamRegistry.start(
                conversationId,
                request == null ? null : request.getClientRequestId(),
                requestContext(
                        "retry-trpg-step",
                        stepPath(conversationId, turnId, stepId)
                                + "/retry",
                        request == null ? null
                                : request.getClientRequestId(),
                        null, null),
                turnExecutionService.retry(
                        conversationId, turnId, stepId));
    }

    @GetMapping("/conversations/{conversationId}/turns/current")
    public Result currentTurn(@PathVariable Long conversationId) {
        return Result.success(
                turnExecutionService.current(conversationId));
    }

    @PostMapping(
            value = "/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/message",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> submitTurnMessage(
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @PathVariable Long stepId,
            @RequestBody GroupChatRequestDTO request) {
        conversationService.requireAuthorized(conversationId);
        return generationStreamRegistry.start(
                conversationId,
                request == null ? null : request.getClientRequestId(),
                requestContext(
                        "submit-trpg-action",
                        stepPath(conversationId, turnId, stepId)
                                + "/message",
                        request == null ? null
                                : request.getClientRequestId(),
                        "content", request == null ? null
                                : request.getContent()),
                turnExecutionService.submitMessage(
                        conversationId, turnId, stepId, request));
    }

    @PostMapping(
            value = "/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/manual-message",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> submitManualMessage(
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @PathVariable Long stepId,
            @RequestBody GroupChatRequestDTO request) {
        conversationService.requireAuthorized(conversationId);
        return generationStreamRegistry.start(
                conversationId,
                request == null ? null : request.getClientRequestId(),
                requestContext(
                        "submit-manual-character-message",
                        stepPath(conversationId, turnId, stepId)
                                + "/manual-message",
                        request == null ? null
                                : request.getClientRequestId(),
                        "content", request == null ? null
                                : request.getContent()),
                groupChatService.submitManualMessage(
                        conversationId, turnId, stepId, request));
    }

    @PostMapping(
            value = "/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/inquiry",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> submitInquiry(
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @PathVariable Long stepId,
            @RequestBody TrpgInvestigatorInquiryDTO request) {
        conversationService.requireAuthorized(conversationId);
        return generationStreamRegistry.start(
                conversationId,
                request == null ? null : request.getClientRequestId(),
                requestContext(
                        "ask-trpg-kp",
                        stepPath(conversationId, turnId, stepId)
                                + "/inquiry",
                        request == null ? null
                                : request.getClientRequestId(),
                        "question", request == null ? null
                                : request.getQuestion()),
                turnExecutionService.submitInquiry(
                        conversationId, turnId, stepId, request));
    }

    @PostMapping(
            value = "/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/selection",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> submitSceneSelection(
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @PathVariable Long stepId,
            @RequestBody GroupSceneSelectionDTO request) {
        conversationService.requireAuthorized(conversationId);
        return generationStreamRegistry.start(
                conversationId,
                request == null ? null : request.getClientRequestId(),
                requestContext(
                        "select-trpg-scene",
                        stepPath(conversationId, turnId, stepId)
                                + "/selection",
                        request == null ? null
                                : request.getClientRequestId(),
                        "optionNo", request == null ? null
                                : request.getOptionNo()),
                turnExecutionService.submitSelection(
                        conversationId, turnId, stepId, request));
    }

    @PostMapping(
            value = "/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/end-exploration",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> endExploration(
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @PathVariable Long stepId,
            @RequestBody GroupEndExplorationDTO request) {
        conversationService.requireAuthorized(conversationId);
        return generationStreamRegistry.start(
                conversationId,
                request == null ? null : request.getClientRequestId(),
                requestContext(
                        "end-trpg-exploration",
                        stepPath(conversationId, turnId, stepId)
                                + "/end-exploration",
                        request == null ? null
                                : request.getClientRequestId(),
                        null, null),
                turnExecutionService.endExploration(
                        conversationId, turnId, stepId, request));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Result listHistory(@PathVariable Long conversationId,
                              @RequestParam(required = false) Long beforeId,
                              @RequestParam(required = false) Integer size) {
        return Result.success(groupChatService.listHistory(conversationId, beforeId, size));
    }

    @PostMapping("/conversations/{conversationId}/withdraw")
    public Result withdrawLatestTurn(@PathVariable Long conversationId) {
        withdrawalService.withdrawLatestTurn(conversationId);
        return Result.success();
    }

    @GetMapping("/conversations/{conversationId}/reply-plan")
    public Result getReplyPlan(@PathVariable Long conversationId) {
        return Result.success(
                replyPlanService.listRemaining(conversationId));
    }

    @PutMapping("/conversations/{conversationId}/reply-plan")
    public Result replaceReplyPlan(@PathVariable Long conversationId,
                                   @RequestBody GroupReplyPlanDTO request) {
        return Result.success(replyPlanService.replace(conversationId, request));
    }

    @DeleteMapping("/conversations/{conversationId}/reply-plan")
    public Result finishReplyPlan(@PathVariable Long conversationId) {
        return Result.success(replyPlanService.finishActive(conversationId));
    }

    private static GenerationRequestContext requestContext(
            String operation,
            String path,
            String clientRequestId,
            String valueKey,
            String value) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (clientRequestId != null) {
            body.put("clientRequestId", clientRequestId);
        }
        if (valueKey != null && value != null) {
            body.put(valueKey, value);
        }
        return new GenerationRequestContext(
                operation, "POST", path, body);
    }

    private static String stepPath(
            Long conversationId, Long turnId, Long stepId) {
        return "/group-chat/conversations/" + conversationId
                + "/turns/" + turnId + "/steps/" + stepId;
    }

}
