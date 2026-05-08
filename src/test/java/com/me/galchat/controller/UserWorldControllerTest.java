package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.UserAuthDTO;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.domain.vo.UserTokenVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.ai.chat.memory.repository.jdbc.initialize-schema=never")
class UserWorldControllerTest {

    @Autowired
    private UserWorldController userWorldController;

    @Autowired
    private UserInfoController userInfoController;

    @Autowired
    private IWorldTemplateService worldTemplateService;

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
    }

    @Test
    void listAndGetWorldTemplatesReadFromDatabase() {
        WorldTemplate template = createWorldTemplate("template");

        Result listResult = userWorldController.listWorldTemplates();

        assertThat(listResult.getCode()).isEqualTo(1);
        assertThat(listResult.getData()).isInstanceOf(List.class);
        List<?> templates = (List<?>) listResult.getData();
        assertThat(templates)
                .extracting(item -> ((WorldTemplate) item).getId())
                .contains(template.getId());

        Result getResult = userWorldController.getWorldTemplate(template.getId());

        assertThat(getResult.getCode()).isEqualTo(1);
        assertThat(getResult.getData()).isInstanceOf(WorldTemplate.class);
        WorldTemplate actual = (WorldTemplate) getResult.getData();
        assertThat(actual.getId()).isEqualTo(template.getId());
        assertThat(actual.getName()).isEqualTo(template.getName());
        assertThat(actual.getImage()).isEqualTo(template.getImage());
    }

    @Test
    void createGetUpdateDeleteUserWorldPersistToDatabase() {
        UserTokenVO user = registerUser();
        CurrentHolder.setCurrentId(user.getId().intValue());
        WorldTemplate template = createWorldTemplate("world-flow");

        UserWorldPrefix createRequest = new UserWorldPrefix()
                .setWorldId(template.getId())
                .setName("integration-user-world")
                .setAcitvePushStatus(true)
                .setPushTime(9)
                .setConnectOtherCharacterStatus(true)
                .setFavorSystemStatus("MEDIUM")
                .setEotDetectionStatus(true);

        Result createResult = userWorldController.createUserWorld(createRequest);

        assertThat(createResult.getCode()).isEqualTo(1);
        assertThat(createResult.getData()).isInstanceOf(UserWorldPrefix.class);
        UserWorldPrefix created = (UserWorldPrefix) createResult.getData();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getUserId()).isEqualTo(user.getId());
        assertThat(created.getWorldId()).isEqualTo(template.getId());
        assertThat(created.getName()).isEqualTo("integration-user-world");
        assertThat(created.getImage()).isEqualTo(template.getImage());

        Result getResult = userWorldController.getUserWorld(created.getId());

        assertThat(getResult.getCode()).isEqualTo(1);
        assertThat(getResult.getData()).isInstanceOf(UserWorldPrefix.class);
        UserWorldPrefix fromDatabase = (UserWorldPrefix) getResult.getData();
        assertThat(fromDatabase.getId()).isEqualTo(created.getId());
        assertThat(fromDatabase.getUserId()).isEqualTo(user.getId());

        UserWorldPrefix updateRequest = new UserWorldPrefix()
                .setName("integration-user-world-updated")
                .setAcitvePushStatus(false)
                .setPushTime(21)
                .setConnectOtherCharacterStatus(false)
                .setFavorSystemStatus("HARD")
                .setEotDetectionStatus(false);

        Result updateResult = userWorldController.updateUserWorld(created.getId(), updateRequest);

        assertThat(updateResult.getCode()).isEqualTo(1);
        assertThat(updateResult.getData()).isInstanceOf(UserWorldPrefix.class);
        UserWorldPrefix updated = (UserWorldPrefix) updateResult.getData();
        assertThat(updated.getName()).isEqualTo("integration-user-world-updated");
        assertThat(updated.getPushTime()).isEqualTo(21);
        assertThat(updated.getFavorSystemStatus()).isEqualTo("HARD");

        Result listResult = userWorldController.listUserWorldBaseInfo(user.getId());

        assertThat(listResult.getCode()).isEqualTo(1);
        assertThat(listResult.getData()).isInstanceOf(List.class);
        List<?> worlds = (List<?>) listResult.getData();
        assertThat(worlds)
                .extracting(item -> ((UserWorldPrefix) item).getId())
                .contains(created.getId());

        Result deleteResult = userWorldController.deleteUserWorld(created.getId());

        assertThat(deleteResult.getCode()).isEqualTo(1);
        assertThat(deleteResult.getData()).isNull();
        assertThatThrownBy(() -> userWorldController.getUserWorld(created.getId()))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("用户世界不存在");
    }

    @Test
    void createUserWorldRejectsMissingWorldIdBeforeDatabaseWrite() {
        UserTokenVO user = registerUser();
        CurrentHolder.setCurrentId(user.getId().intValue());

        assertThatThrownBy(() -> userWorldController.createUserWorld(new UserWorldPrefix()))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("世界模板id不能为空");
    }

    private WorldTemplate createWorldTemplate(String prefix) {
        String suffix = UUID.randomUUID().toString();
        WorldTemplate template = new WorldTemplate()
                .setName("test-" + prefix + "-" + suffix)
                .setImage("https://example.com/" + suffix + ".png")
                .setAuthor("integration-test")
                .setBackground("integration test background");
        assertThat(worldTemplateService.save(template)).isTrue();
        assertThat(template.getId()).isNotNull();
        return template;
    }

    private UserTokenVO registerUser() {
        UserAuthDTO userAuthDTO = new UserAuthDTO();
        userAuthDTO.setEmail("test-world-" + UUID.randomUUID() + "@example.com");
        userAuthDTO.setPassword("password123");

        Result result = userInfoController.register(userAuthDTO);

        assertThat(result.getCode()).isEqualTo(1);
        assertThat(result.getData()).isInstanceOf(UserTokenVO.class);
        return (UserTokenVO) result.getData();
    }
}
