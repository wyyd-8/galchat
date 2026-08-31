package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.service.impl.StepwiseCharacterCardCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/character-card-creation/rules")
@RequiredArgsConstructor
public class CharacterCardCreationRulesController {

    private final StepwiseCharacterCardCreationService service;

    @GetMapping
    public Result getRules() {
        return Result.success(service.getRules());
    }
}
