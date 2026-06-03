package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.UserWorldSaveCreateDTO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.IUserWorldSaveService;
import com.me.galchat.utils.CurrentHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/world-saves")
@RequiredArgsConstructor
public class UserWorldSaveController {

    private final IUserWorldSaveService userWorldSaveService;

    @GetMapping("/{userWorldId}")
    public Result getSave(@PathVariable Long userWorldId) {
        checkUserWorldId(userWorldId);
        return Result.success(userWorldSaveService.getSave(currentUserId(), userWorldId));
    }

    @PostMapping("/{userWorldId}")
    public Result saveWorld(@PathVariable Long userWorldId, @RequestBody(required = false) UserWorldSaveCreateDTO createDTO) {
        checkUserWorldId(userWorldId);
        return Result.success(userWorldSaveService.saveWorld(currentUserId(), userWorldId, createDTO));
    }

    @PostMapping("/{userWorldId}/load")
    public Result loadWorld(@PathVariable Long userWorldId) {
        checkUserWorldId(userWorldId);
        userWorldSaveService.loadWorld(currentUserId(), userWorldId);
        return Result.success();
    }

    private void checkUserWorldId(Long userWorldId) {
        if (userWorldId == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
    }

    private Long currentUserId() {
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        return Long.valueOf(userId);
    }
}
