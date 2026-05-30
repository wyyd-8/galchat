package com.me.galchat.controller;


import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.UserCharacterPromptDTO;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.ICharacterTemplateService;
import com.me.galchat.service.IUserCharacterInfoService;
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
 * @since 2026-05-08
 */
@RestController
@RequestMapping("/character")
@RequiredArgsConstructor
public class UserCharacterController {

    private final IUserCharacterInfoService userCharacterInfoService;
    private final ICharacterTemplateService characterTemplateService;

    @PostMapping("/templates/{worldId}")
    public Result createCharacterTemplate(@PathVariable Long worldId, @RequestBody CharacterTemplate characterTemplate) {
        checkWorldId(worldId);
        characterTemplateService.createCharacterTemplate(currentUserId(), worldId, characterTemplate);
        return Result.success();
    }

    @GetMapping("/templates/{worldId}")
    public Result listCharacterTemplates(@PathVariable Long worldId) {
        checkWorldId(worldId);
        return Result.success(characterTemplateService.listCharacterBaseInfoByWorldId(currentUserId(), worldId));
    }

    @PostMapping("/{userWorldId}/{characterId}")
    public Result addCharacter(@PathVariable Long userWorldId, @PathVariable Long characterId) {
        checkUserWorldId(userWorldId);
        checkCharacterId(characterId);
        userCharacterInfoService.addCharacter(userWorldId, characterId);
        return Result.success();
    }

    @DeleteMapping("/{userWorldId}/{characterId}")
    public Result deleteCharacter(@PathVariable Long userWorldId, @PathVariable Long characterId) {
        checkUserWorldId(userWorldId);
        checkCharacterId(characterId);
        userCharacterInfoService.deleteCharacter(userWorldId, characterId);
        return Result.success();
    }

    @GetMapping("/{userWorldId}")
    public Result listCharacters(@PathVariable Long userWorldId) {
        checkUserWorldId(userWorldId);
        return Result.success(userCharacterInfoService.listByUserWorldId(userWorldId));
    }

    @PutMapping("/{userWorldId}/{characterId}/prompt")
    public Result updateUserInfoPrompt(@PathVariable Long userWorldId, @PathVariable Long characterId,
                                       @RequestBody UserCharacterPromptDTO promptDTO) {
        checkUserWorldId(userWorldId);
        checkCharacterId(characterId);
        if (promptDTO == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        userCharacterInfoService.updateUserInfoPrompt(userWorldId, characterId, promptDTO.getUserInfoPrompt());
        return Result.success();
    }

    private void checkUserWorldId(Long userWorldId) {
        if (userWorldId == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
    }

    private void checkWorldId(Long worldId) {
        if (worldId == null) {
            throw new UserRequestException("世界模板id不能为空");
        }
    }

    private void checkCharacterId(Long characterId) {
        if (characterId == null) {
            throw new UserRequestException("角色id不能为空");
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
