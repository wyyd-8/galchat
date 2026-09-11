package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.service.IDiceRollService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class DiceRollController {

    private final IDiceRollService diceRollService;

    @GetMapping("/dice-rolls/{id}")
    public Result getSummary(@PathVariable Long id) {
        return Result.success(diceRollService.getSummary(id));
    }

    @GetMapping("/dice-rolls/{id}/results")
    public Result listResults(@PathVariable Long id) {
        return Result.success(diceRollService.listResults(id));
    }

    @PostMapping("/dice-roll-results/{id}/roll")
    public Result roll(@PathVariable Long id) {
        return Result.success(diceRollService.roll(id));
    }
}
