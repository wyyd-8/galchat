package com.me.galchat.config;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.*;
import com.me.galchat.mapper.*;
import com.me.galchat.service.*;
import com.me.galchat.service.impl.chat.*;
import com.me.galchat.service.impl.user.UserCharacterInfoServiceImpl;
import com.me.galchat.service.impl.world.UserWorldPrefixServiceImpl;
import com.me.galchat.support.MybatisPlusTestSupport;
import com.me.galchat.utils.RedisCacheExpiry;
import org.junit.jupiter.api.*;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Run only against the disposable Redis instance supplied by the test runner. */
class RedisCacheExpiryIntegrationTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        String port = System.getenv("GALCHAT_TEST_REDIS_PORT");
        Assumptions.assumeTrue(port != null, "requires a disposable Redis via GALCHAT_TEST_REDIS_PORT");
        connection = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(port));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        // The supplied instance is dedicated to this suite, never an application database.
        try (var client = connection.getConnection()) { client.serverCommands().flushDb(); }
        MybatisPlusTestSupport.initialize(UserCharacterInfo.class, UserWorldPrefix.class, UserCharacterFavorLog.class);
    }

    @AfterEach
    void close() { if (connection != null) connection.destroy(); }

    @Test
    void sharedHashWritesCannotExtendExistingEntriesForever() {
        String key = RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY;
        redis.opsForHash().put(key, "3:7", "50");
        RedisCacheExpiry.ensure(redis, key, Duration.ofSeconds(10));
        Long deadline = redis.getExpire(key, TimeUnit.MILLISECONDS);
        redis.opsForHash().put(key, "4:8", "60");
        RedisCacheExpiry.ensure(redis, key, Duration.ofMinutes(5));
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, deadline);
        redis.expireAt(key, Instant.EPOCH);
        assertThat(redis.opsForHash().get(key, "3:7")).isNull();
        RedisCacheExpiry.ensure(redis, "missing", Duration.ofMinutes(5));
        assertThat(redis.hasKey("missing")).isFalse();
    }

    @Test
    void upgradesLegacyCachesWithoutExpiringBusinessStateOrExtendingExistingDeadlines() {
        var properties = new CacheProperties();
        properties.getRedis().setTimeToLive(Duration.ofHours(1));
        var config = new RedisConfig().cacheConfiguration(properties);
        redis.opsForHash().put(RedisConstant.WORLD_USER_AUTH_KEY, "3", "1");
        redis.opsForHash().put(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, "3:7", "50");
        redis.opsForHash().put("user:character:prompt:3:7", "userInfoPrompt", "legacy");
        redis.opsForValue().set("characterTemplate::7", "legacy");
        redis.opsForValue().set("worldPrompt::2", "legacy");
        redis.opsForValue().set("worldPrompt::9", "already expires", Duration.ofSeconds(10));
        redis.opsForValue().set("chat:topic:boundary:3:7", "business state");
        redis.opsForList().rightPush(RedisConstant.USER_EVENT_LOG_DELAY_QUEUE_NAME, "queued task");

        new RedisCacheExpiryMigration(redis, config).run(null);

        assertTtl(RedisConstant.WORLD_USER_AUTH_KEY, 1800);
        assertTtl(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, 300);
        assertTtl("user:character:prompt:3:7", 300);
        assertTtl("characterTemplate::7", 3600);
        assertTtl("worldPrompt::2", 3600);
        assertTtl("worldPrompt::9", 10);
        assertThat(redis.getExpire("chat:topic:boundary:3:7")).isEqualTo(-1L);
        assertThat(redis.getExpire(RedisConstant.USER_EVENT_LOG_DELAY_QUEUE_NAME)).isEqualTo(-1L);
        assertThat(redis.opsForValue().get("characterTemplate::7")).isEqualTo("legacy");
    }

    @Test
    void annotationCachesUseConfiguredTtlAndHaveAFiniteDefault() {
        var defaults = new RedisConfig().cacheConfiguration(new CacheProperties());
        assertThat(defaults.getTtlFunction().getTimeToLive("key", "value")).isEqualTo(Duration.ofHours(1));
        var properties = new CacheProperties();
        properties.getRedis().setTimeToLive(Duration.ofSeconds(20));
        var manager = RedisCacheManager.builder(connection)
                .cacheDefaults(new RedisConfig().cacheConfiguration(properties)).build();
        manager.afterPropertiesSet();
        manager.getCache("characterTemplate").put(7L, "template");
        manager.getCache("worldPrompt").put(2L, "prompt");
        // Spring Data Redis may write annotation caches asynchronously.
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertTtl("characterTemplate::7", 20);
            assertTtl("worldPrompt::2", 20);
        });
    }

    @Test
    void promptExpiryReloadsCurrentDatabaseValuesAndFavorWritesHaveDeadlines() {
        var mapper = mock(UserCharacterInfoMapper.class);
        when(mapper.selectOne(any())).thenReturn(
                new UserCharacterInfo().setFavorValue(50).setUserInfoPrompt("旧资料"),
                new UserCharacterInfo().setFavorValue(50).setUserInfoPrompt("新资料"));
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.compareAndSetFavorValue(3L, 7L, 50, 51)).thenReturn(1);
        var templates = mock(ICharacterTemplateService.class);
        when(templates.getCharacterTemplateById(7L)).thenReturn(new CharacterTemplate().setName("角色"));
        var service = new UserCharacterInfoServiceImpl(templates, mock(IUserWorldPrefixService.class), redis,
                mock(UserCharacterFavorLogMapper.class), mock(UserChatHistoryMapper.class), mock(UserChatThinkingHistoryMapper.class),
                mock(UserChatToolCallMapper.class), mock(UserEventLogMapper.class), mock(GroupChatMemberMapper.class),
                mock(SingleChatLockService.class), mock(SingleChatGenerationRegistry.class), mock(VectorStoreCleanupMapper.class), mock(com.me.galchat.service.impl.group.GroupConversationLockService.class));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "entityClass", UserCharacterInfo.class);
        assertThat(service.buildCharacterPrompt(3L, 7L)).contains("旧资料");
        assertTtl("user:character:prompt:3:7", 300);
        redis.expireAt("user:character:prompt:3:7", Instant.EPOCH);
        assertThat(service.buildCharacterPrompt(3L, 7L)).contains("新资料");
        service.setFavorValue(3L, 7L, 50);
        assertTtl(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, 300);
        redis.expireAt(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, Instant.EPOCH);
        service.updateFavorValue(3L, 7L, 1, "single_message", 20L);
        assertTtl(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, 300);
        assertThat(redis.opsForHash().get(RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY, "3:7")).isEqualTo("51");
    }

    @Test
    void worldAuthorizationCacheExpiresAndFallsBackToDatabase() {
        var mapper = mock(UserWorldPrefixMapper.class);
        var world = new UserWorldPrefix().setId(3L).setUserId(1L);
        when(mapper.selectOne(any())).thenReturn(world);
        var service = new UserWorldPrefixServiceImpl(mock(IWorldTemplateService.class), redis, mock(UserCharacterInfoMapper.class),
                mock(com.me.galchat.mapper.GroupConversationMapper.class), mock(com.me.galchat.mapper.UserWorldSaveMapper.class),
                mock(com.me.galchat.service.impl.group.GroupConversationLockService.class),
                mock(com.me.galchat.service.impl.group.GroupConversationDeletionStore.class),
                mock(com.me.galchat.service.ITrpgRedisStateService.class),
                mock(com.me.galchat.service.impl.group.GroupGenerationStreamRegistry.class));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "entityClass", UserWorldPrefix.class);
        assertThat(service.checkUserWorldAuth(1L, 3L, false)).isSameAs(world);
        assertTtl(RedisConstant.WORLD_USER_AUTH_KEY, 1800);
        assertThat(service.checkUserWorldAuth(1L, 3L, false)).isNull();
        redis.expireAt(RedisConstant.WORLD_USER_AUTH_KEY, Instant.EPOCH);
        assertThat(service.checkUserWorldAuth(1L, 3L, false)).isSameAs(world);
        verify(mapper, times(2)).selectOne(any());
    }

    private void assertTtl(String key, long seconds) {
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween((seconds - 2) * 1000, seconds * 1000);
    }
}
