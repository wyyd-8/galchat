package com.me.galchat.service.impl.user;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.dto.UserAuthDTO;
import com.me.galchat.service.IUserInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UserInfoServiceImplTest {

    @Autowired
    private IUserInfoService service;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createIsolatedRegistrationTables() {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE user_info (
                    id BIGSERIAL PRIMARY KEY,
                    username VARCHAR(255) NOT NULL,
                    email VARCHAR(255) UNIQUE NOT NULL,
                    password VARCHAR(255) NOT NULL,
                    birthday DATE,
                    dice_skin VARCHAR(50) NOT NULL,
                    create_time TIMESTAMP
                ) ON COMMIT DROP
                """);
        jdbcTemplate.execute("""
                CREATE TEMP TABLE user_model_api (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    name VARCHAR(100) NOT NULL,
                    base_url VARCHAR(1000) NOT NULL,
                    model_name VARCHAR(255) NOT NULL,
                    api_key_encrypted TEXT NOT NULL,
                    api_key_hint VARCHAR(32) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    chat_capability VARCHAR(20) NOT NULL,
                    streaming_capability VARCHAR(20) NOT NULL,
                    tool_calling_capability VARCHAR(20) NOT NULL,
                    reasoning_output_status VARCHAR(20) NOT NULL,
                    last_test_code VARCHAR(50),
                    last_test_message VARCHAR(1000),
                    last_test_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                ) ON COMMIT DROP
                """);
    }

    @Test
    void registrationDoesNotProvisionAModelConfiguration() {
        String email = "registration-model-test@bjtu.edu.cn";
        String code = "123456";
        String verificationKey = RedisConstant.EMAIL_VERIFY_CODE_KEY_PREFIX
                + email + ":" + code;
        redisTemplate.opsForValue().set(verificationKey, email);
        try {
            UserAuthDTO request = new UserAuthDTO();
            request.setEmail(email);
            request.setPassword("secret");
            request.setVerificationCode(code);

            var token = service.register(request);

            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM user_model_api
                    WHERE user_id = ?
                    """, Integer.class, token.getId())).isZero();
        } finally {
            redisTemplate.delete(verificationKey);
        }
    }
}
