package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.UserAuthDTO;
import com.me.galchat.domain.dto.UserPasswordDTO;
import com.me.galchat.domain.dto.UserProfileDTO;
import com.me.galchat.domain.po.UserInfo;
import com.me.galchat.domain.vo.UserTokenVO;
import com.me.galchat.service.IUserInfoService;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.chat.memory.repository.jdbc.initialize-schema=never")
class UserInfoControllerTest {

    @Autowired
    private UserInfoController userInfoController;

    @Autowired
    private IUserInfoService userInfoService;

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    @Test
    void registerAndLoginReturnToken() {
        String email = uniqueEmail("auth");
        String password = "password123";

        Result registerResult = userInfoController.register(authDTO(email, password));

        assertThat(registerResult.getCode()).isEqualTo(1);
        assertThat(registerResult.getData()).isInstanceOf(UserTokenVO.class);
        UserTokenVO registerToken = (UserTokenVO) registerResult.getData();
        assertThat(registerToken.getId()).isNotNull();
        assertThat(registerToken.getUsername()).isEqualTo("undefined");
        assertThat(registerToken.getToken()).isNotBlank();

        Result loginResult = userInfoController.login(authDTO(email, password));

        assertThat(loginResult.getCode()).isEqualTo(1);
        assertThat(loginResult.getData()).isInstanceOf(UserTokenVO.class);
        UserTokenVO loginToken = (UserTokenVO) loginResult.getData();
        assertThat(loginToken.getId()).isEqualTo(registerToken.getId());
        assertThat(loginToken.getToken()).isNotBlank();
    }

    @Test
    void getAndUpdateUserInfoUseCurrentUserId() {
        TestUser testUser = registerUser("profile", "password123");
        CurrentHolder.setCurrentId(testUser.token().getId().intValue());

        Result getResult = userInfoController.getUserInfo();

        assertThat(getResult.getCode()).isEqualTo(1);
        assertThat(getResult.getData()).isInstanceOf(UserInfo.class);
        UserInfo userInfo = (UserInfo) getResult.getData();
        assertThat(userInfo.getId()).isEqualTo(testUser.token().getId());
        assertThat(userInfo.getEmail()).isEqualTo(testUser.email());
        assertThat(userInfo.getPassword()).isNull();

        UserProfileDTO profileDTO = new UserProfileDTO();
        profileDTO.setUsername("updated-user");
        profileDTO.setEmail(uniqueEmail("updated"));
        profileDTO.setBirthday(LocalDate.of(2000, 1, 2));

        Result updateResult = userInfoController.updateUserInfo(profileDTO);

        assertThat(updateResult.getCode()).isEqualTo(1);
        assertThat(updateResult.getData()).isInstanceOf(UserInfo.class);
        UserInfo updated = (UserInfo) updateResult.getData();
        assertThat(updated.getId()).isEqualTo(testUser.token().getId());
        assertThat(updated.getUsername()).isEqualTo("updated-user");
        assertThat(updated.getEmail()).isEqualTo(profileDTO.getEmail());
        assertThat(updated.getBirthday()).isEqualTo(LocalDate.of(2000, 1, 2));
        assertThat(updated.getPassword()).isNull();
    }

    @Test
    void updatePasswordPersistsNewPassword() {
        String oldPassword = "oldPassword123";
        String newPassword = "newPassword123";
        TestUser testUser = registerUser("password", oldPassword);
        UserInfo userInfo = userInfoService.getInfoById(testUser.token().getId().intValue());
        CurrentHolder.setCurrentId(testUser.token().getId().intValue());

        UserPasswordDTO passwordDTO = new UserPasswordDTO();
        passwordDTO.setEmail(userInfo.getEmail());
        passwordDTO.setOldPassword(oldPassword);
        passwordDTO.setNewPassword(newPassword);

        Result updateResult = userInfoController.updatePassword(passwordDTO);

        assertThat(updateResult.getCode()).isEqualTo(1);
        assertThat(updateResult.getMsg()).isEqualTo("success");
        assertThat(updateResult.getData()).isNull();

        Result loginWithNewPassword = userInfoController.login(authDTO(userInfo.getEmail(), newPassword));
        assertThat(loginWithNewPassword.getCode()).isEqualTo(1);
        assertThat(loginWithNewPassword.getData()).isInstanceOf(UserTokenVO.class);
    }

    private TestUser registerUser(String prefix, String password) {
        String email = uniqueEmail(prefix);
        Result result = userInfoController.register(authDTO(email, password));
        assertThat(result.getCode()).isEqualTo(1);
        assertThat(result.getData()).isInstanceOf(UserTokenVO.class);
        return new TestUser(email, (UserTokenVO) result.getData());
    }

    private UserAuthDTO authDTO(String email, String password) {
        UserAuthDTO userAuthDTO = new UserAuthDTO();
        userAuthDTO.setEmail(email);
        userAuthDTO.setPassword(password);
        return userAuthDTO;
    }

    private String uniqueEmail(String prefix) {
        return "test-" + prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private record TestUser(String email, UserTokenVO token) {
    }
}
