package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.service.impl.trpg.TrpgParticipantHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/group-chat/participant-history")
@RequiredArgsConstructor
public class TrpgParticipantHistoryController {
    private final TrpgParticipantHistoryService service;

    @GetMapping
    public Result summaries(@RequestParam Long userWorldId) {
        return Result.success(service.summaries(userWorldId));
    }

    @GetMapping("/{characterId}/runs")
    public Result runs(@RequestParam Long userWorldId, @PathVariable Long characterId,
                       @RequestParam(required = false) String cursor,
                       @RequestParam(defaultValue = "10") int limit) {
        return Result.success(service.runs(userWorldId, characterId, cursor, limit));
    }
}
