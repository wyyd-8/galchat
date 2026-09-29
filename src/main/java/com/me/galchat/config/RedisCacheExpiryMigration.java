package com.me.galchat.config;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.utils.RedisCacheExpiry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Upgrade cache deadlines and remove obsolete deadlines from durable TRPG workflow state. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisCacheExpiryMigration implements ApplicationRunner {
    private final StringRedisTemplate redis;
    private final RedisCacheConfiguration cacheConfiguration;

    @Override
    public void run(ApplicationArguments args) {
        try {
            persistMatching(RedisConstant.TRPG_SCENE_PROGRESS_PREFIX + "*");
            persistMatching(RedisConstant.TRPG_SCENE_SELECTION_PREFIX + "*");
            RedisCacheExpiry.ensure(redis, RedisConstant.WORLD_USER_AUTH_KEY, RedisConstant.WORLD_USER_AUTH_TTL);
            RedisCacheExpiry.ensure(redis, RedisConstant.USER_CHARACTER_FAVOR_VALUE_KEY,
                    RedisConstant.USER_CHARACTER_FAVOR_VALUE_TTL);
            expireMatching(RedisConstant.USER_CHARACTER_PROMPT_INFO_KEY_PREFIX + "*",
                    RedisConstant.USER_CHARACTER_PROMPT_INFO_TTL);
            var templateTtl = cacheConfiguration.getTtlFunction().getTimeToLive(null, null);
            expireMatching(cacheConfiguration.getKeyPrefixFor("characterTemplate") + "*", templateTtl);
            expireMatching(cacheConfiguration.getKeyPrefixFor("worldPrompt") + "*", templateTtl);
        } catch (RuntimeException error) {
            log.warn("Redis 键过期策略迁移失败，下次启动时重试", error);
        }
    }

    private void persistMatching(String pattern) {
        try (var keys = redis.scan(ScanOptions.scanOptions().match(pattern)
                .count(RedisConstant.REDIS_SCAN_COUNT).build())) {
            while (keys.hasNext()) redis.persist(keys.next());
        }
    }

    private void expireMatching(String pattern, Duration ttl) {
        try (var keys = redis.scan(ScanOptions.scanOptions().match(pattern)
                .count(RedisConstant.REDIS_SCAN_COUNT).build())) {
            while (keys.hasNext()) RedisCacheExpiry.ensure(redis, keys.next(), ttl);
        }
    }
}
