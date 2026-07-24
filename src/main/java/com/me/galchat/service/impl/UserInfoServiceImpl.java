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
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.utils.JwtUtils;
import com.me.galchat.utils.AliyunEmailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
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
@RequiredArgsConstructor
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final AliyunEmailSender emailSender;

    @Override
    public UserInfo getInfoById(Integer id) {
        return lambdaQuery()
                .select(UserInfo::getId, UserInfo::getUsername, UserInfo::getEmail, UserInfo::getBirthday,
                        UserInfo::getDiceSkin, UserInfo::getCreateTime)
                .eq(UserInfo::getId, id)
                .one();
    }

    @Override
    public UserTokenVO login(UserAuthDTO userAuthDTO) {
        if (userAuthDTO == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        String email = normalizeEmail(userAuthDTO.getEmail());
        checkEmailAndPassword(email, userAuthDTO.getPassword());

        UserInfo userInfo = getByEmail(email);
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
        String email = normalizeEmail(userAuthDTO.getEmail());
        checkEmailAndPassword(email, userAuthDTO.getPassword());

        UserInfo existed = getByEmail(email);
        if (existed != null) {
            throw new UserRequestException("邮箱已被注册");
        }
        String verificationCode = checkEmailVerificationCode(email, userAuthDTO.getVerificationCode());

        UserInfo userInfo = new UserInfo()
                .setUsername(UserConstant.DEFAULT_USERNAME)
                .setEmail(email)
                .setPassword(encodePassword(userAuthDTO.getPassword()))
                .setDiceSkin(UserConstant.DEFAULT_DICE_SKIN)
                .setCreateTime(LocalDateTime.now());
        save(userInfo);
        redisTemplate.delete(buildEmailVerifyCodeKey(email, verificationCode));
        return buildUserToken(userInfo);
    }

    @Override
    public void sendRegisterEmailVerificationCode(String email) {
        String normalizedEmail = normalizeEmail(email);
        if (!StringUtils.hasText(normalizedEmail)) {
            throw new UserRequestException("邮箱不能为空");
        }
        if (!normalizedEmail.matches("[0-9]{8}@bjtu.edu.cn")) {
            throw new UserRequestException("测试阶段，请使用校园邮箱");
        }

        UserInfo existed = getByEmail(normalizedEmail);
        if (existed != null) {
            throw new UserRequestException("邮箱已被注册");
        }
        checkEmailVerifyNotFrozen(normalizedEmail);

        String cooldownKey = buildEmailVerifyCooldownKey(normalizedEmail);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey, "1", RedisConstant.EMAIL_VERIFY_COOLDOWN_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new UserRequestException("验证码发送过于频繁，请1分钟后再试");
        }

        String verificationCode = generateVerificationCode();
        String codeKey = buildEmailVerifyCodeKey(normalizedEmail, verificationCode);
        redisTemplate.opsForValue().set(codeKey, normalizedEmail, RedisConstant.EMAIL_VERIFY_CODE_TTL);

        boolean sent = emailSender.sendSimpleMail(normalizedEmail, UserConstant.EMAIL_VERIFICATION_SUBJECT,
                buildEmailVerificationContent(verificationCode));
        if (!sent) {
            redisTemplate.delete(codeKey);
            redisTemplate.delete(cooldownKey);
            throw new UserRequestException("验证码邮件发送失败，请稍后重试");
        }
    }

    @Override
    public void sendPasswordEmailVerificationCode(Integer userId, String email) {
        String normalizedEmail = normalizeEmail(email);
        if (!StringUtils.hasText(normalizedEmail)) {
            throw new UserRequestException("邮箱不能为空");
        }

        UserInfo userInfo = getExistingUser(userId);
        if (!normalizedEmail.equals(userInfo.getEmail())) {
            throw new UserAuthException("邮箱与当前用户不匹配");
        }
        checkEmailVerifyNotFrozen(normalizedEmail);

        String cooldownKey = buildEmailVerifyCooldownKey(normalizedEmail);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey, "1", RedisConstant.EMAIL_VERIFY_COOLDOWN_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new UserRequestException("验证码发送过于频繁，请1分钟后再试");
        }

        String verificationCode = generateVerificationCode();
        String codeKey = buildEmailVerifyCodeKey(normalizedEmail, verificationCode);
        redisTemplate.opsForValue().set(codeKey, normalizedEmail, RedisConstant.EMAIL_VERIFY_CODE_TTL);

        boolean sent = emailSender.sendSimpleMail(normalizedEmail, UserConstant.EMAIL_VERIFICATION_SUBJECT,
                buildEmailVerificationContent(verificationCode));
        if (!sent) {
            redisTemplate.delete(codeKey);
            redisTemplate.delete(cooldownKey);
            throw new UserRequestException("验证码邮件发送失败，请稍后重试");
        }
    }

    @Override
    public void updateUserInfo(Integer userId, UserProfileDTO userProfileDTO) {
        if (userProfileDTO == null) {
            throw new UserRequestException("请求参数不能为空");
        }

        String diceSkin = userProfileDTO.getDiceSkin();
        if (diceSkin != null) {
            diceSkin = diceSkin.trim();
            if (!StringUtils.hasText(diceSkin)) {
                throw new UserRequestException("骰子皮肤不能为空");
            }
            if (diceSkin.length() > UserConstant.DICE_SKIN_MAX_LENGTH) {
                throw new UserRequestException("骰子皮肤标识不能超过50个字符");
            }
        }

        UserInfo updateUserInfo = new UserInfo()
                .setId(Long.valueOf(userId))
                .setUsername(StringUtils.hasText(userProfileDTO.getUsername()) ? userProfileDTO.getUsername() : null)
                .setBirthday(userProfileDTO.getBirthday())
                .setDiceSkin(diceSkin);
        updateById(updateUserInfo);
    }

    @Override
    public void updatePassword(Integer userId, UserPasswordDTO userPasswordDTO) {
        if (userPasswordDTO == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        String email = normalizeEmail(userPasswordDTO.getEmail());
        if (!StringUtils.hasText(email)
                || !StringUtils.hasText(userPasswordDTO.getNewPassword())) {
            throw new UserRequestException("邮箱和新密码不能为空");
        }

        UserInfo userInfo = getExistingUser(userId);
        if (!email.equals(userInfo.getEmail())) {
            throw new UserAuthException("邮箱与当前用户不匹配");
        }
        String verificationCode = checkEmailVerificationCode(email, userPasswordDTO.getVerificationCode());

        UserInfo updateUserInfo = new UserInfo()
                .setId(Long.valueOf(userId))
                .setPassword(encodePassword(userPasswordDTO.getNewPassword()));
        updateById(updateUserInfo);
        redisTemplate.delete(buildEmailVerifyCodeKey(email, verificationCode));
    }

    private void checkEmailAndPassword(String email, String password) {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            throw new UserRequestException("邮箱和密码不能为空");
        }
    }

    private String checkEmailVerificationCode(String email, String verificationCode) {
        checkEmailVerifyNotFrozen(email);
        String normalizedCode = normalizeVerificationCode(verificationCode);
        if (!StringUtils.hasText(normalizedCode)) {
            recordEmailVerifyFailedAttempt(email);
            throw new UserRequestException("邮箱验证码不能为空");
        }
        if (!normalizedCode.matches("\\d{6}")) {
            recordEmailVerifyFailedAttempt(email);
            throw new UserRequestException("邮箱验证码错误或已过期");
        }
        String savedEmail = redisTemplate.opsForValue().get(buildEmailVerifyCodeKey(email, normalizedCode));
        if (!email.equals(savedEmail)) {
            recordEmailVerifyFailedAttempt(email);
            throw new UserRequestException("邮箱验证码错误或已过期");
        }
        redisTemplate.delete(buildEmailVerifyAttemptKey(email));
        return normalizedCode;
    }

    private void checkEmailVerifyNotFrozen(String email) {
        Boolean frozen = redisTemplate.hasKey(buildEmailVerifyFreezeKey(email));
        if (Boolean.TRUE.equals(frozen)) {
            throw new UserRequestException("邮箱验证码尝试次数过多，该邮箱已冻结30分钟");
        }
    }

    private void recordEmailVerifyFailedAttempt(String email) {
        String attemptKey = buildEmailVerifyAttemptKey(email);
        Long attempts = redisTemplate.opsForValue().increment(attemptKey);
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(attemptKey, RedisConstant.EMAIL_VERIFY_ATTEMPT_TTL);
        }
        if (attempts != null && attempts > RedisConstant.EMAIL_VERIFY_MAX_ATTEMPTS) {
            redisTemplate.opsForValue().set(buildEmailVerifyFreezeKey(email), "1",
                    RedisConstant.EMAIL_VERIFY_FREEZE_TTL);
            redisTemplate.delete(attemptKey);
            throw new UserRequestException("邮箱验证码尝试次数过多，该邮箱已冻结30分钟");
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

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim();
    }

    private String normalizeVerificationCode(String verificationCode) {
        return verificationCode == null ? null : verificationCode.trim();
    }

    private String generateVerificationCode() {
        return String.format(Locale.ROOT, "%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private String buildEmailVerifyCodeKey(String email, String verificationCode) {
        return RedisConstant.EMAIL_VERIFY_CODE_KEY_PREFIX + email + ":" + verificationCode;
    }

    private String buildEmailVerifyCooldownKey(String email) {
        return RedisConstant.EMAIL_VERIFY_COOLDOWN_KEY_PREFIX + email;
    }

    private String buildEmailVerifyAttemptKey(String email) {
        return RedisConstant.EMAIL_VERIFY_ATTEMPT_KEY_PREFIX + email;
    }

    private String buildEmailVerifyFreezeKey(String email) {
        return RedisConstant.EMAIL_VERIFY_FREEZE_KEY_PREFIX + email;
    }

    private String buildEmailVerificationContent(String verificationCode) {
        return "尊敬的用户，您好！\n\n"
                + "您正在进行邮箱验证，本次操作的验证码如下：\n\n"
                + verificationCode + "\n\n"
                + "验证码 5分钟 内有效，为保障您的账户安全，请勿向任何人泄露。\n\n"
                + "如非本人操作，请忽略此邮件。\n\n"
                + "GalChat 团队";
    }
}
