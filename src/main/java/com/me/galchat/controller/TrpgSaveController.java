package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.TrpgSaveCreateDTO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.ITrpgSaveService;
import com.me.galchat.utils.CurrentHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trpg-saves")
@RequiredArgsConstructor
public class TrpgSaveController {

    private final ITrpgSaveService trpgSaveService;

    @GetMapping("/{conversationId}")
    public Result getSave(@PathVariable Long conversationId) {
        checkConversationId(conversationId);
        return Result.success(trpgSaveService.getSave(
                currentUserId(), conversationId));
    }

    @PostMapping("/{conversationId}")
    public Result save(
            @PathVariable Long conversationId,
            @RequestBody(required = false) TrpgSaveCreateDTO createDTO) {
        checkConversationId(conversationId);
        return Result.success(trpgSaveService.save(
                currentUserId(), conversationId, createDTO));
    }

    @PostMapping("/{conversationId}/load")
    public Result load(@PathVariable Long conversationId) {
        checkConversationId(conversationId);
        trpgSaveService.load(currentUserId(), conversationId);
        return Result.success();
    }

    @PostMapping("/{conversationId}/rollback-turn")
    public Result rollbackTurn(@PathVariable Long conversationId) {
        checkConversationId(conversationId);
        return Result.success(trpgSaveService.rollbackTurn(
                currentUserId(), conversationId));
    }

    @GetMapping("/{conversationId}/rollback-status")
    public Result rollbackStatus(@PathVariable Long conversationId) {
        checkConversationId(conversationId);
        return Result.success(trpgSaveService.getRollbackOverview(
                currentUserId(), conversationId));
    }

    @PostMapping("/{conversationId}/rollback-scene")
    public Result rollbackScene(@PathVariable Long conversationId) {
        checkConversationId(conversationId);
        return Result.success(trpgSaveService.rollbackScene(
                currentUserId(), conversationId));
    }

    @PostMapping("/{conversationId}/rollback-initial")
    public Result rollbackInitial(@PathVariable Long conversationId) {
        checkConversationId(conversationId);
        return Result.success(trpgSaveService.rollbackInitial(
                currentUserId(), conversationId));
    }

    private void checkConversationId(Long conversationId) {
        if (conversationId == null) {
            throw new UserRequestException("跑团群聊id不能为空");
        }
    }

    private Long currentUserId() {
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        return Long.valueOf(userId);
    }
}
