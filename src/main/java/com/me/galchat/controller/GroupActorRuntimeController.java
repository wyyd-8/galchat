package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.GroupActorRuntimeSaveDTO;
import com.me.galchat.service.impl.GroupActorRuntimeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/group-chat/conversations/{conversationId}/actor-runtimes")
public class GroupActorRuntimeController {

    private final GroupActorRuntimeService service;

    public GroupActorRuntimeController(GroupActorRuntimeService service) {
        this.service = service;
    }

    @GetMapping
    public Result list(@PathVariable Long conversationId) {
        return Result.success(service.list(conversationId));
    }

    @PutMapping
    public Result save(
            @PathVariable Long conversationId,
            @RequestBody GroupActorRuntimeSaveDTO dto) {
        return Result.success(service.save(conversationId, dto));
    }
}
