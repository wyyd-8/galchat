package com.me.galchat.controller;


import com.me.galchat.domain.Result;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldDetail;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.IWorldDetailService;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.utils.CurrentHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
@RequestMapping("/world")
@RequiredArgsConstructor
public class UserWorldController {

    private final IUserWorldPrefixService userWorldPrefixService;
    private final IWorldTemplateService worldTemplateService;
    private final IWorldDetailService worldDetailService;

    @GetMapping("/templates")
    public Result listWorldTemplates() {
        return Result.success(worldTemplateService.listWorldBaseInfo(currentUserId()));
    }

    @GetMapping("/templates/{id}")
    public Result getWorldTemplate(@PathVariable Long id) {
        if (id == null) {
            throw new UserRequestException("世界模板id不能为空");
        }
        return Result.success(worldTemplateService.getWorldTemplateById(currentUserId(), id));
    }

    @PostMapping("/templates")
    public Result createWorldTemplate(@RequestBody WorldTemplate worldTemplate) {
        worldTemplateService.createWorldTemplate(currentUserId(), worldTemplate);
        return Result.success();
    }

    @GetMapping("/templates/{worldId}/details")
    public Result listWorldDetails(@PathVariable Long worldId) {
        if (worldId == null) {
            throw new UserRequestException("世界模板id不能为空");
        }
        return Result.success(worldDetailService.listWorldDetails(currentUserId(), worldId));
    }

    @PostMapping("/templates/{worldId}/details")
    public Result createWorldDetail(@PathVariable Long worldId, @RequestBody WorldDetail worldDetail) {
        if (worldId == null) {
            throw new UserRequestException("世界模板id不能为空");
        }
        worldDetailService.createWorldDetail(currentUserId(), worldId, worldDetail);
        return Result.success();
    }

    @PostMapping
    public Result createUserWorld(@RequestBody UserWorldPrefix userWorldPrefix) {
        if (userWorldPrefix == null || userWorldPrefix.getWorldId() == null) {
            throw new UserRequestException("世界模板id不能为空");
        }
        userWorldPrefixService.createUserWorld(currentUserId(), userWorldPrefix);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result getUserWorld(@PathVariable Long id) {
        if (id == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        return Result.success(userWorldPrefixService.getUserWorld(currentUserId(), id));
    }

    @PutMapping("/{id}")
    public Result updateUserWorld(@PathVariable Long id, @RequestBody UserWorldPrefix userWorldPrefix) {
        if (id == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        if (userWorldPrefix == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        userWorldPrefixService.updateUserWorld(currentUserId(), id, userWorldPrefix);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result deleteUserWorld(@PathVariable Long id) {
        if (id == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        userWorldPrefixService.deleteUserWorld(currentUserId(), id);
        return Result.success();
    }

    @GetMapping("/user/{userId}")
    public Result listUserWorldBaseInfo(@PathVariable Long userId) {
        if (userId == null) {
            throw new UserRequestException("用户id不能为空");
        }
        return Result.success(userWorldPrefixService.listBaseInfoByUserId(userId));
    }

    private Long currentUserId() {
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        return Long.valueOf(userId);
    }
}
