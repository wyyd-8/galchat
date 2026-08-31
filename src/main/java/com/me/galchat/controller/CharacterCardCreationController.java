package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.domain.dto.StepwiseCharacterCardModels;
import com.me.galchat.service.impl.CharacterCardCreationService;
import com.me.galchat.service.impl.StepwiseCharacterCardCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/character-card-creation/drafts")
@RequiredArgsConstructor
public class CharacterCardCreationController {

    private final CharacterCardCreationService service;
    private final StepwiseCharacterCardCreationService stepService;

    @PostMapping("/auto")
    public Result createAuto(
            @RequestBody CharacterCardGenerationModels.CreateRequest request) {
        return Result.success(service.createAuto(request));
    }

    @PostMapping("/step")
    public Result createStep(
            @RequestBody StepwiseCharacterCardModels.CreateRequest request) {
        return Result.success(stepService.create(request));
    }

    @GetMapping("/{id}")
    public Result get(@PathVariable Long id) {
        return Result.success(service.get(id));
    }

    @GetMapping("/active")
    public Result getActive(
            @RequestParam Long runId,
            @RequestParam(required = false) Long participantId) {
        return Result.success(service.getActive(runId, participantId));
    }

    @DeleteMapping("/{id}")
    public Result abandon(
            @PathVariable Long id,
            @RequestParam Integer expectedVersion) {
        return Result.success(service.abandon(id, expectedVersion));
    }

    @PatchMapping("/{id}/identity")
    public Result updateIdentity(
            @PathVariable Long id,
            @RequestBody StepwiseCharacterCardModels.IdentityUpdateRequest request) {
        return Result.success(stepService.updateIdentity(id, request));
    }

    @PostMapping("/{id}/attributes/roll")
    public Result rollAttributes(
            @PathVariable Long id,
            @RequestBody CharacterCardGenerationModels.ActionRequest request) {
        return Result.success(stepService.rollAttributes(id, request));
    }

    @PutMapping("/{id}/age-adjustment")
    public Result applyAgeAdjustment(
            @PathVariable Long id,
            @RequestBody StepwiseCharacterCardModels.AgeAdjustmentRequest request) {
        return Result.success(stepService.applyAgeAdjustment(id, request));
    }

    @PutMapping("/{id}/occupation")
    public Result saveOccupation(
            @PathVariable Long id,
            @RequestBody StepwiseCharacterCardModels.OccupationRequest request) {
        return Result.success(stepService.saveOccupation(id, request));
    }

    @PutMapping("/{id}/skills")
    public Result saveSkills(
            @PathVariable Long id,
            @RequestBody StepwiseCharacterCardModels.SkillsRequest request) {
        return Result.success(stepService.saveSkills(id, request));
    }

    @PostMapping("/{id}/background/{category}/roll")
    public Result rollBackground(
            @PathVariable Long id,
            @PathVariable String category,
            @RequestBody StepwiseCharacterCardModels.BackgroundRollRequest request) {
        return Result.success(stepService.rollBackground(id, category, request));
    }

    @PutMapping("/{id}/background")
    public Result saveBackground(
            @PathVariable Long id,
            @RequestBody StepwiseCharacterCardModels.BackgroundRequest request) {
        return Result.success(stepService.saveBackground(id, request));
    }

    @PutMapping("/{id}/equipment")
    public Result saveEquipment(
            @PathVariable Long id,
            @RequestBody StepwiseCharacterCardModels.EquipmentRequest request) {
        return Result.success(stepService.saveEquipment(id, request));
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
