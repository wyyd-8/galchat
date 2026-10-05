package com.me.galchat.service.impl.user;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.me.galchat.domain.dto.UserProfileDTO;
import com.me.galchat.domain.po.UserInfo;
import com.me.galchat.mapper.UserInfoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class UserProfileUpdateTest {
    @Test
    void explicitEmptyBirthdayWritesNullWithoutChangingOtherAccountFields() {
        assertUpdate("{\"birthday\":\"\"}", true);
    }

    @Test
    void explicitNullBirthdayWritesNull() {
        assertUpdate("{\"birthday\":null}", true);
    }

    @Test
    void omittedBirthdayDoesNotClearAnExistingBirthday() {
        assertUpdate("{\"username\":\"Alice\"}", false);
    }

    @Test
    void anExplicitDateUpdatesTheBirthday() {
        assertUpdate("{\"birthday\":\"2000-01-02\"}", true, java.time.LocalDate.of(2000, 1, 2));
    }

    private void assertUpdate(String json, boolean clearsBirthday) {
        assertUpdate(json, clearsBirthday, null);
    }

    private void assertUpdate(String json, boolean clearsBirthday, Object expectedBirthday) {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(UserInfoMapper.class);
        var mapper = mock(UserInfoMapper.class);
        var service = new UserInfoServiceImpl(null, null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        var statements = new java.util.ArrayList<String>();
        org.mockito.stubbing.Answer<Integer> inspectSql = invocation -> {
            var parameters = new HashMap<String, Object>();
            parameters.put("et", invocation.getArgument(0));
            if (invocation.getArguments().length > 1) parameters.put("ew", invocation.getArgument(1));
            var sql = configuration.getMappedStatement(UserInfoMapper.class.getName() + (parameters.containsKey("ew") ? ".update" : ".updateById"))
                    .getBoundSql(parameters);
            statements.add(sql.getSql());
            if (clearsBirthday) {
                assertThat(sql.getSql()).contains("birthday").doesNotContain("password", "email", "dice_skin");
                assertThat(sql.getSql()).contains("id");
                var values = sql.getParameterMappings().stream()
                        .map(mapping -> configuration.newMetaObject(parameters).getValue(mapping.getProperty()))
                        .collect(java.util.stream.Collectors.toList());
                assertThat(values).contains(expectedBirthday);
            } else {
                assertThat(sql.getSql()).contains("username").doesNotContain("birthday");
            }
            return 1;
        };
        doAnswer(inspectSql).when(mapper).update(nullable(UserInfo.class), any(Wrapper.class));
        doAnswer(inspectSql).when(mapper).updateById(any(UserInfo.class));
        service.updateUserInfo(1, JsonMapper.builder().build().readValue(json, UserProfileDTO.class));
        assertThat(statements).hasSize(1);
    }
}
