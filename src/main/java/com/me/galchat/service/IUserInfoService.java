package com.me.galchat.service;

import com.me.galchat.domain.po.UserInfo;
import com.me.galchat.domain.dto.UserAuthDTO;
import com.me.galchat.domain.dto.UserPasswordDTO;
import com.me.galchat.domain.dto.UserProfileDTO;
import com.me.galchat.domain.vo.UserTokenVO;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
public interface IUserInfoService extends IService<UserInfo> {
    UserInfo getInfoById(Integer id);

    UserTokenVO login(UserAuthDTO userAuthDTO);

    UserTokenVO register(UserAuthDTO userAuthDTO);

    void sendRegisterEmailVerificationCode(String email);

    void sendPasswordEmailVerificationCode(Integer userId, String email);

    void updateUserInfo(Integer userId, UserProfileDTO userProfileDTO);

    void updatePassword(Integer userId, UserPasswordDTO userPasswordDTO);
}
