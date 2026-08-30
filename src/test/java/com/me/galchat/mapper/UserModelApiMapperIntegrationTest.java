package com.me.galchat.mapper;

import com.me.galchat.domain.po.UserModelApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UserModelApiMapperIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260829_2__user_model_api.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserModelApiMapper mapper;

    @BeforeEach
    void createSchema() throws Exception {
        String schema = "model_api_mapper_" + UUID.randomUUID()
                .toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA \"" + schema + "\"");
        jdbcTemplate.execute("SET LOCAL search_path TO \"" + schema + "\"");
        jdbcTemplate.execute(Files.readString(MIGRATION));
    }

    @Test
    void mapperScopesReadUpdateAndDeleteByUserId() {
        UserModelApi owned = record(7L, "主模型");
        UserModelApi other = record(8L, "主模型");
        mapper.insert(owned);
        mapper.insert(other);

        assertThat(mapper.selectByUserId(7L))
                .extracting(UserModelApi::getId)
                .containsExactly(owned.getId());
        assertThat(mapper.selectOwned(owned.getId(), 8L)).isNull();

        owned.setUserId(8L).setName("越权更新");
        assertThat(mapper.updateOwned(owned)).isZero();
        assertThat(mapper.deleteOwned(owned.getId(), 8L)).isZero();
        assertThat(mapper.selectOwned(owned.getId(), 7L).getName())
                .isEqualTo("主模型");
        assertThat(mapper.selectOwned(owned.getId(), 7L).getRequestOverrides())
                .isEqualTo(Map.of(
                        "thinking", Map.of("type", "enabled"),
                        "reasoning_effort", "high"));

        owned.setUserId(7L)
                .setStatus("PARTIAL")
                .setToolCallingCapability("INCONCLUSIVE")
                .setLastTestCode("CAPABILITY_PARTIAL")
                .setLastTestMessage("部分能力未确认")
                .setLastTestAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        assertThat(mapper.updateTestResult(owned)).isEqualTo(1);
        assertThat(mapper.selectOwned(owned.getId(), 7L).getStatus())
                .isEqualTo("PARTIAL");

        assertThat(mapper.deleteOwned(owned.getId(), 7L)).isEqualTo(1);
        assertThat(mapper.selectOwned(owned.getId(), 7L)).isNull();
    }

    private UserModelApi record(Long userId, String name) {
        LocalDateTime now = LocalDateTime.now();
        return new UserModelApi()
                .setUserId(userId)
                .setName(name)
                .setBaseUrl("https://models.example.com/v1")
                .setModelName("model-a")
                .setRequestOverrides(Map.of(
                        "thinking", Map.of("type", "enabled"),
                        "reasoning_effort", "high"))
                .setApiKeyEncrypted("v1:nonce:ciphertext")
                .setApiKeyHint("…test")
                .setStatus("UNTESTED")
                .setChatCapability("UNKNOWN")
                .setStreamingCapability("UNKNOWN")
                .setToolCallingCapability("UNKNOWN")
                .setReasoningOutputStatus("UNKNOWN")
                .setCreatedAt(now)
                .setUpdatedAt(now);
    }
}
