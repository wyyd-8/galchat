package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.service.impl.CharacterCardCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/character-card-creation/drafts")
@RequiredArgsConstructor
public class CharacterCardCreationController {

    private final CharacterCardCreationService service;

    @PostMapping("/auto")
    public Result createAuto(
            @RequestBody CharacterCardGenerationModels.CreateRequest request) {
        return Result.success(service.createAuto(request));
    }

    @GetMapping("/{id}")
    public Result get(@PathVariable Long id) {
        return Result.success(service.get(id));
    }

    @GetMapping("/active")
    public Result getActive(
            @RequestParam Long runId,
            @RequestParam Long participantId) {
        return Result.success(service.getActive(runId, participantId));
    }

    @PostMapping("/{id}/regenerate")
    public Result regenerate(
            @PathVariable Long id,
            @RequestBody CharacterCardGenerationModels.ActionRequest request) {
        return Result.success(service.regenerate(id, request));
    }

    @PostMapping("/{id}/rewrite-background")
    public Result rewriteBackground(
            @PathVariable Long id,
            @RequestBody CharacterCardGenerationModels.ActionRequest request) {
        return Result.success(service.rewriteBackground(id, request));
    }

    @PostMapping("/{id}/complete")
    public Result complete(
            @PathVariable Long id,
            @RequestBody CharacterCardGenerationModels.ActionRequest request) {
        return Result.success(service.complete(id, request));
    }
}
