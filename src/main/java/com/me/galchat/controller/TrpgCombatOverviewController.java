package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.service.impl.group.GroupConversationService;
import com.me.galchat.service.impl.trpg.TrpgCombatOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/group-chat/conversations/{conversationId}/combat-overview")
@RequiredArgsConstructor
public class TrpgCombatOverviewController {

    private final GroupConversationService conversationService;
    private final TrpgCombatOverviewService overviewService;

    @GetMapping
    public Result list(@PathVariable Long conversationId) {
        conversationService.requireAuthorized(conversationId);
        return Result.success(overviewService.list(conversationId));
    }
}
