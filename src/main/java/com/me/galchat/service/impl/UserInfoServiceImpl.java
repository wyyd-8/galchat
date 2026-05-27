package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.UserAuthDTO;
import com.me.galchat.constant.UserConstant;
import com.me.galchat.domain.dto.UserPasswordDTO;
import com.me.galchat.domain.dto.UserProfileDTO;
import com.me.galchat.domain.po.UserInfo;
import com.me.galchat.domain.vo.UserTokenVO;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserNotFoundException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserInfoMapper;
import com.me.galchat.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.utils.JwtUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

    @Override
    public UserInfo getInfoById(Integer id) {
        return lambdaQuery()
                .select(UserInfo::getId, UserInfo::getUsername, UserInfo::getEmail, UserInfo::getBirthday, UserInfo::getCreateTime)
                .eq(UserInfo::getId, id)
                .one();
    }

    @Override
    public UserTokenVO login(UserAuthDTO userAuthDTO) {
        if (userAuthDTO == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        checkEmailAndPassword(userAuthDTO.getEmail(), userAuthDTO.getPassword());

        UserInfo userInfo = getByEmail(userAuthDTO.getEmail());
        if (userInfo == null || !matchesPassword(userAuthDTO.getPassword(), userInfo.getPassword())) {
            throw new UserAuthException("邮箱或密码错误");
        }
        return buildUserToken(userInfo);
    }

    @Override
    public UserTokenVO register(UserAuthDTO userAuthDTO) {
        if (userAuthDTO == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        checkEmailAndPassword(userAuthDTO.getEmail(), userAuthDTO.getPassword());
        // TODO 进行邮箱校验，例如校验邮箱验证码或邮箱可达性。

        UserInfo existed = getByEmail(userAuthDTO.getEmail());
        if (existed != null) {
            throw new UserRequestException("邮箱已被注册");
        }

        UserInfo userInfo = new UserInfo()
                .setUsername(UserConstant.DEFAULT_USERNAME)
                .setEmail(userAuthDTO.getEmail())
                .setPassword(encodePassword(userAuthDTO.getPassword()))
                .setCreateTime(LocalDateTime.now());
        save(userInfo);
        return buildUserToken(userInfo);
    }

    @Override
    public void updateUserInfo(Integer userId, UserProfileDTO userProfileDTO) {
        if (userProfileDTO == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        UserInfo oldUserInfo = getExistingUser(userId);

        if (StringUtils.hasText(userProfileDTO.getEmail())) {
            UserInfo existed = getByEmail(userProfileDTO.getEmail());
            if (existed != null && !existed.getId().equals(oldUserInfo.getId())) {
                throw new UserRequestException("邮箱已被占用");
            }
        }

        UserInfo updateUserInfo = new UserInfo()
                .setId(Long.valueOf(userId))
                .setUsername(StringUtils.hasText(userProfileDTO.getUsername()) ? userProfileDTO.getUsername() : null)
                .setEmail(StringUtils.hasText(userProfileDTO.getEmail()) ? userProfileDTO.getEmail() : null)
                .setBirthday(userProfileDTO.getBirthday());
        updateById(updateUserInfo);
    }

    @Override
    public void updatePassword(Integer userId, UserPasswordDTO userPasswordDTO) {
        if (userPasswordDTO == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        if (!StringUtils.hasText(userPasswordDTO.getEmail())
                || !StringUtils.hasText(userPasswordDTO.getOldPassword())
                || !StringUtils.hasText(userPasswordDTO.getNewPassword())) {
            throw new UserRequestException("邮箱、旧密码和新密码不能为空");
        }
        // TODO 进行邮箱校验，例如校验邮箱验证码或邮箱与当前用户的绑定关系。

        UserInfo userInfo = getExistingUser(userId);
        if (!userPasswordDTO.getEmail().equals(userInfo.getEmail())) {
            throw new UserAuthException("邮箱与当前用户不匹配");
        }
        if (!matchesPassword(userPasswordDTO.getOldPassword(), userInfo.getPassword())) {
            throw new UserAuthException("旧密码错误");
        }

        UserInfo updateUserInfo = new UserInfo()
                .setId(Long.valueOf(userId))
                .setPassword(encodePassword(userPasswordDTO.getNewPassword()));
        updateById(updateUserInfo);
    }

    private void checkEmailAndPassword(String email, String password) {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            throw new UserRequestException("邮箱和密码不能为空");
        }
    }

    private UserInfo getByEmail(String email) {
        return lambdaQuery()
                .eq(UserInfo::getEmail, email)
                .one();
    }

    private UserInfo getExistingUser(Integer userId) {
        UserInfo userInfo = getById(userId);
        if (userInfo == null) {
            throw new UserNotFoundException("用户不存在");
        }
        return userInfo;
    }

    private UserTokenVO buildUserToken(UserInfo userInfo) {
        String token = JwtUtils.generateToken(Map.of(
                "id", userInfo.getId().intValue(),
                "username", userInfo.getUsername()
        ));
        return new UserTokenVO(token, userInfo.getId(), userInfo.getUsername());
    }

    private String encodePassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encoded);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256算法不可用", e);
        }
    }

    private boolean matchesPassword(String rawPassword, String savedPassword) {
        if (!StringUtils.hasText(savedPassword)) {
            return false;
        }
        return encodePassword(rawPassword).equals(savedPassword);
    }
}
