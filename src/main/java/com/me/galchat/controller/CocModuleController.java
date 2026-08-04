package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.service.impl.CocModuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/coc-modules")
@RequiredArgsConstructor
public class CocModuleController {

    private final CocModuleService moduleService;

    @GetMapping
    public Result list() {
        return Result.success(moduleService.listVisible());
    }

    @GetMapping("/{id}")
    public Result detail(@PathVariable Long id) {
        return Result.success(moduleService.getVisible(id));
    }
}
