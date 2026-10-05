package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.CharacterCardCreateDTO;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.character.CharacterCardAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/character-cards")
@RequiredArgsConstructor
public class CharacterCardController {

    private final ICharacterCardService characterCardService;
    private final CharacterCardAccessService access;

    @PostMapping
    public Result create(@RequestBody CharacterCardCreateDTO createDTO) {
        access.requireRunAccess(createDTO == null ? null : createDTO.getRunId());
        return Result.success(characterCardService.create(createDTO));
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        access.requireCardAccess(id);
        characterCardService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        access.requireCardAccess(id);
        return Result.success(characterCardService.getById(id));
    }

    @GetMapping("/investigators")
    public Result listInvestigators(@RequestParam Long runId) {
        access.requireRunAccess(runId);
        return Result.success(characterCardService.listInvestigatorCards(runId));
    }

    @PostMapping("/{id}/luck")
    public Result rollLuck(@PathVariable Long id) {
        access.requireCardAccess(id);
        return Result.success(characterCardService.rollLuck(id));
    }
}
