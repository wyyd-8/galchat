package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.service.impl.trpg.TrpgCompletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/group-chat/conversations/{conversationId}")
@RequiredArgsConstructor
public class TrpgCompletionController {
    private final TrpgCompletionService completionService;

    @GetMapping("/completion-report")
    public Result get(@PathVariable Long conversationId) {
        return Result.success(completionService.get(conversationId));
    }

}
