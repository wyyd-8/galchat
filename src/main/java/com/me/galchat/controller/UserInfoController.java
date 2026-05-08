package com.me.galchat.controller;


import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.UserAuthDTO;
import com.me.galchat.domain.dto.UserPasswordDTO;
import com.me.galchat.domain.dto.UserProfileDTO;
import com.me.galchat.service.IUserInfoService;
import com.me.galchat.utils.CurrentHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserInfoController {

    private final IUserInfoService userInfoService;

    @PostMapping("/login")
    public Result login(@RequestBody UserAuthDTO userAuthDTO) {
        return Result.success(userInfoService.login(userAuthDTO));
    }

    @PostMapping("/register")
    public Result register(@RequestBody UserAuthDTO userAuthDTO) {
        return Result.success(userInfoService.register(userAuthDTO));
    }

    @GetMapping("/info")
    public Result getUserInfo() {
        return Result.success(userInfoService.getInfoById(CurrentHolder.getCurrentId()));
    }

    @PutMapping("/info")
    public Result updateUserInfo(@RequestBody UserProfileDTO userProfileDTO) {
        return Result.success(userInfoService.updateUserInfo(CurrentHolder.getCurrentId(), userProfileDTO));
    }

    @PutMapping("/password")
    public Result updatePassword(@RequestBody UserPasswordDTO userPasswordDTO) {
        userInfoService.updatePassword(CurrentHolder.getCurrentId(), userPasswordDTO);
        return Result.success();
    }
}
