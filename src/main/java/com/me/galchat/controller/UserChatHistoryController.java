package com.me.galchat.controller;


import com.me.galchat.domain.Result;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.IUserChatHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
@RestController
@RequestMapping("/history")
@RequiredArgsConstructor
public class UserChatHistoryController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final IUserChatHistoryService userChatHistoryService;

    @GetMapping
    public Result listHistory(@RequestParam("userworldid") Long userWorldId,
                              @RequestParam("characterid") Long characterId,
                              @RequestParam(value = "id", required = false) Long id,
                              @RequestParam(value = "size", required = false) Integer size) {
        checkRequest(userWorldId, characterId);
        size = size == null ? DEFAULT_PAGE_SIZE : size;
        if (size <= 0) {
            throw new UserRequestException("查询条数必须大于0");
        }

        return Result.success(userChatHistoryService.listHistory(userWorldId, characterId, id, size));
    }

    private void checkRequest(Long userWorldId, Long characterId) {
        if (userWorldId == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        if (characterId == null) {
            throw new UserRequestException("角色id不能为空");
        }
    }
}
