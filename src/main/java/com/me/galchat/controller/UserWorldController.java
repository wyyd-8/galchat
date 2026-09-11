package com.me.galchat.controller;


import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.WorldArchiveDTO;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldDetail;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.IWorldArchiveService;
import com.me.galchat.service.IWorldDetailService;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.utils.CurrentHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

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
    private final IWorldArchiveService worldArchiveService;
    private final ObjectMapper objectMapper;

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

    @GetMapping("/templates/my/{userWorldId}")
    public Result getMyWorldTemplate(@PathVariable Long userWorldId) {
        checkUserWorldId(userWorldId);
        Long userId = currentUserId();
        UserWorldPrefix userWorld = getMyWorld(userId, userWorldId);
        return Result.success(worldTemplateService.getOwnWorldTemplate(userId, userWorld.getWorldId()));
    }

    @PutMapping("/templates/my/{userWorldId}")
    public Result updateMyWorldTemplate(@PathVariable Long userWorldId, @RequestBody WorldTemplate worldTemplate) {
        checkUserWorldId(userWorldId);
        Long userId = currentUserId();
        UserWorldPrefix userWorld = getMyWorld(userId, userWorldId);
        worldTemplateService.updateWorldTemplate(userId, userWorld.getWorldId(), worldTemplate);
        return Result.success();
    }

    @GetMapping("/templates/my/{userWorldId}/export")
    public ResponseEntity<String> exportMyWorld(@PathVariable Long userWorldId) {
        checkUserWorldId(userWorldId);
        WorldArchiveDTO archive = worldArchiveService.exportMyWorld(currentUserId(), userWorldId);
        String filename = "galchat-world-" + userWorldId + ".json";
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(archive);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping("/import")
    public Result importWorld(@RequestBody WorldArchiveDTO archive) {
        return Result.success(worldArchiveService.importWorld(currentUserId(), archive));
    }

    @GetMapping("/templates/{id}/usage")
    public Result getWorldTemplateUsage(@PathVariable Long id) {
        checkWorldTemplateId(id);
        return Result.success(worldArchiveService.getWorldTemplateUsage(currentUserId(), id));
    }

    @PutMapping("/templates/{id}/replace")
    public Result replaceWorldTemplate(@PathVariable Long id,
                                       @RequestParam(defaultValue = "false") boolean confirmLowMatch,
                                       @RequestBody WorldArchiveDTO archive) {
        checkWorldTemplateId(id);
        return Result.success(worldArchiveService.replaceWorldTemplate(
                currentUserId(), id, archive, confirmLowMatch));
    }

    @DeleteMapping("/templates/{id}")
    public Result deleteWorldTemplate(@PathVariable Long id) {
        checkWorldTemplateId(id);
        worldArchiveService.deleteWorldTemplate(currentUserId(), id);
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

    @DeleteMapping("/templates/{worldId}/{detailId}")
    public Result deleteWorldDetail(@PathVariable Long worldId, @PathVariable Long detailId) {
        if (worldId == null) {
            throw new UserRequestException("世界模板id不能为空");
        }
        worldDetailService.deleteWorldDetail(currentUserId(), worldId, detailId);
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

    @DeleteMapping("/{userWorldId}")
    public Result deleteUserWorld(@PathVariable Long userWorldId) {
        if (userWorldId == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
        userWorldPrefixService.deleteUserWorld(currentUserId(), userWorldId);
        return Result.success();
    }

    @GetMapping("/user/{userId}")
    public Result listUserWorldBaseInfo(@PathVariable Long userId) {
        if (userId == null) {
            throw new UserRequestException("用户id不能为空");
        }
        return Result.success(userWorldPrefixService.listBaseInfoByUserId(userId));
    }

    private void checkUserWorldId(Long userWorldId) {
        if (userWorldId == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
    }

    private void checkWorldTemplateId(Long worldId) {
        if (worldId == null) {
            throw new UserRequestException("世界模板id不能为空");
        }
    }

    private UserWorldPrefix getMyWorld(Long userId, Long userWorldId) {
        UserWorldPrefix userWorld = userWorldPrefixService.checkUserWorldAuth(userId, userWorldId, true);
        if (!Boolean.TRUE.equals(userWorld.getMyWorld())) {
            throw new UserAuthException("无权操作该世界模板");
        }
        return userWorld;
    }

    private Long currentUserId() {
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        return Long.valueOf(userId);
    }
}
