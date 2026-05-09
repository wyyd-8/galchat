package com.me.galchat.controller;


import com.me.galchat.domain.Result;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.IUserCharacterInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/{userWorldId}/{characterId}")
    public Result addCharacter(@PathVariable Long userWorldId, @PathVariable Long characterId) {
        checkUserWorldId(userWorldId);
        checkCharacterId(characterId);
        return Result.success(userCharacterInfoService.addCharacter(userWorldId, characterId));
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

    private void checkUserWorldId(Long userWorldId) {
        if (userWorldId == null) {
            throw new UserRequestException("用户世界id不能为空");
        }
    }

    private void checkCharacterId(Long characterId) {
        if (characterId == null) {
            throw new UserRequestException("角色id不能为空");
        }
    }
}
