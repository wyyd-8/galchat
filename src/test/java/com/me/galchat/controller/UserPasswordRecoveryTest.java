package com.me.galchat.controller;

import com.me.galchat.config.WebConfig;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.UserInfo;
import com.me.galchat.exception.GlobalExceptionHandler;
import com.me.galchat.interceptor.TokenInterceptor;
import com.me.galchat.mapper.UserInfoMapper;
import com.me.galchat.service.impl.user.UserInfoServiceImpl;
import com.me.galchat.support.MybatisPlusTestSupport;
import com.me.galchat.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserPasswordRecoveryTest {
    private static final String EMAIL = "12345678@bjtu.edu.cn";
    private static final String CODE_KEY = RedisConstant.EMAIL_VERIFY_CODE_KEY_PREFIX + EMAIL + ":123456";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private UserInfoMapper mapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;

    @TestConfiguration
    @EnableWebMvc
    static class Config {
        @Bean UserInfoMapper mapper() { return mock(UserInfoMapper.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean JwtUtils jwt() { return new JwtUtils(java.util.Base64.getEncoder().encodeToString(new byte[32])); }
        @Bean UserInfoServiceImpl service(UserInfoMapper mapper, StringRedisTemplate redis, JwtUtils jwt) {
            var service = new UserInfoServiceImpl(redis, jwt);
            ReflectionTestUtils.setField(service, "baseMapper", mapper);
            ReflectionTestUtils.setField(service, "entityClass", UserInfo.class);
            return service;
        }
        @Bean UserInfoController controller(UserInfoServiceImpl service) { return new UserInfoController(service); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
        @Bean TokenInterceptor tokens(JwtUtils jwt) { return new TokenInterceptor(jwt); }
        @Bean WebConfig webConfig() { return new WebConfig(); }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        MybatisPlusTestSupport.initialize(UserInfo.class);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mapper = context.getBean(UserInfoMapper.class);
        redis = context.getBean(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var user = new UserInfo().setId(42L).setEmail(EMAIL).setPassword("old-password-hash");
        when(mapper.selectOne(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            query.getSqlSegment();
            return query.getParamNameValuePairs().containsValue(EMAIL) ? user : null;
        });
        when(mapper.selectById(42)).thenReturn(user);
        when(values.get(CODE_KEY)).thenReturn(EMAIL);
        when(values.setIfAbsent(anyString(), eq("1"), eq(RedisConstant.EMAIL_VERIFY_COOLDOWN_TTL))).thenReturn(true);
    }

    @AfterEach
    void close() { context.close(); }

    @Test
    void loginTokenIsAcceptedByTheConfiguredHttpInterceptor() throws Exception {
        var user = new UserInfo().setId(42L).setUsername("test-user").setEmail(EMAIL)
                .setPassword("fc97bb52861fcf328d0a7abe201f9632b8703e436656348f6d1b398f42e92c39");
        doReturn(user).when(mapper).selectOne(any());
        var response = mvc.perform(post("/user/login").contentType("application/json")
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"new-secret\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andReturn().getResponse();
        String token = new ObjectMapper().readTree(response.getContentAsString()).get("data").get("token").asString();

        assertThat(context.getBean(JwtUtils.class).parseToken(token).get("id", Integer.class)).isEqualTo(42);
        mvc.perform(get("/user/info").header("token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void httpInterceptorRejectsTokensSignedWithADifferentKey() throws Exception {
        byte[] otherKey = new byte[32];
        java.util.Arrays.fill(otherKey, (byte) 1);
        String token = new JwtUtils(java.util.Base64.getEncoder().encodeToString(otherKey))
                .generateToken(java.util.Map.of("id", 42));

        mvc.perform(get("/user/info").header("token", token)).andExpect(status().isUnauthorized());
    }

    @Test
    void resetsPasswordWithoutLoginAndConsumesCode() throws Exception {
        mvc.perform(put("/user/password/reset").contentType("application/json")
                .content(payload(" 123456 ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        var update = ArgumentCaptor.forClass(UserInfo.class);
        verify(mapper).updateById(update.capture());
        assertThat(update.getValue().getId()).isEqualTo(42L);
        assertThat(update.getValue().getPassword()).isEqualTo(
                "fc97bb52861fcf328d0a7abe201f9632b8703e436656348f6d1b398f42e92c39");
        verify(redis).delete(CODE_KEY);
    }

    @Test
    void rejectsMissingWrongExpiredOrOtherEmailCodes() throws Exception {
        for (String code : new String[]{"", "000000", "abcdef", "654321"}) {
            when(values.get(RedisConstant.EMAIL_VERIFY_CODE_KEY_PREFIX + EMAIL + ":654321"))
                    .thenReturn("another@bjtu.edu.cn");
            mvc.perform(put("/user/password/reset").contentType("application/json").content(payload(code)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        }
        verify(mapper, never()).updateById(any(UserInfo.class));
        verify(redis, never()).delete(CODE_KEY);
    }

    @Test
    void rejectsFrozenEmailEvenWithValidCode() throws Exception {
        when(redis.hasKey(RedisConstant.EMAIL_VERIFY_FREEZE_KEY_PREFIX + EMAIL)).thenReturn(true);
        mvc.perform(put("/user/password/reset").contentType("application/json").content(payload("123456")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(mapper, never()).updateById(any(UserInfo.class));
    }

    @Test
    void returnsExpiringCodeForAutofillWithoutLoginAndEnforcesCooldown() throws Exception {
        var response = mvc.perform(post("/user/password/reset/email-code").contentType("application/json")
                .content("{\"email\":\" " + EMAIL + " \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.matchesPattern("\\d{6}")))
                .andReturn();
        String code = com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.data");
        verify(values).set(RedisConstant.EMAIL_VERIFY_CODE_KEY_PREFIX + EMAIL + ":" + code,
                EMAIL, RedisConstant.EMAIL_VERIFY_CODE_TTL);
        when(values.setIfAbsent(anyString(), eq("1"), eq(RedisConstant.EMAIL_VERIFY_COOLDOWN_TTL))).thenReturn(false);
        mvc.perform(post("/user/password/reset/email-code").contentType("application/json")
                .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void rejectsUnknownEmailAndBlankPassword() throws Exception {
        mvc.perform(put("/user/password/reset").contentType("application/json")
                .content(payload("123456").replace("new-secret", "   ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        doReturn(null).when(mapper).selectOne(any());
        mvc.perform(put("/user/password/reset").contentType("application/json").content(payload("123456")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(mapper, never()).updateById(any(UserInfo.class));
    }

    @Test
    void existingAccountEndpointsStillRequireLogin() throws Exception {
        mvc.perform(put("/user/password").contentType("application/json").content(payload("123456")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/user/password/email-code").contentType("application/json")
                .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/user/info")).andExpect(status().isUnauthorized());
    }

    private String payload(String code) {
        return "{\"email\":\" " + EMAIL + " \",\"newPassword\":\"new-secret\",\"verificationCode\":\"" + code + "\"}";
    }
}
