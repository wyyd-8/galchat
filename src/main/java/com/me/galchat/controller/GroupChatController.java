package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.dto.GroupConversationCreateDTO;
import com.me.galchat.domain.dto.GroupReplyPlanDTO;
import com.me.galchat.domain.dto.GroupEndExplorationDTO;
import com.me.galchat.domain.dto.GroupTurnContinueDTO;
import com.me.galchat.domain.dto.GroupSceneSelectionDTO;
import com.me.galchat.domain.dto.TrpgGameTimeUpdateDTO;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.service.impl.GroupChatService;
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

@RestController
@RequestMapping("/group-chat")
@RequiredArgsConstructor
public class GroupChatController {

    private final GroupConversationService conversationService;
    private final GroupConversationLifecycleService lifecycleService;
    private final GroupChatService groupChatService;
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
        return groupChatService.chat(conversationId, request);
    }

    @PostMapping(
            value = "/conversations/{conversationId}/turns/continue",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> continueTurn(
            @PathVariable Long conversationId,
            @RequestBody GroupTurnContinueDTO request) {
        return turnExecutionService.continueTurn(
                conversationId, request);
    }

    @PostMapping(
            value = "/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/retry",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> retryTurnStep(
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @PathVariable Long stepId) {
        return turnExecutionService.retry(
                conversationId, turnId, stepId);
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
        return turnExecutionService.submitMessage(
                conversationId, turnId, stepId, request);
    }

    @PostMapping(
            value = "/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/selection",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> submitSceneSelection(
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @PathVariable Long stepId,
            @RequestBody GroupSceneSelectionDTO request) {
        return turnExecutionService.submitSelection(
                conversationId, turnId, stepId, request);
    }

    @PostMapping(
            value = "/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/end-exploration",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> endExploration(
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @PathVariable Long stepId,
            @RequestBody GroupEndExplorationDTO request) {
        return turnExecutionService.endExploration(
                conversationId, turnId, stepId, request);
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
        return Result.success(replyPlanService.getActive(conversationId));
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

    @PostMapping("/conversations/{conversationId}/reply-plan/advance")
    public Result advanceReplyPlan(@PathVariable Long conversationId) {
        return Result.success(replyPlanService.advanceGroup(conversationId));
    }
}
