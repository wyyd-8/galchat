package com.me.galchat.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

/** Attach a deadline without letting writes to a shared hash prolong old entries forever. */
public final class RedisCacheExpiry {
    private static final DefaultRedisScript<Long> EXPIRE_IF_PERSISTENT = new DefaultRedisScript<>("""
            if redis.call('PTTL', KEYS[1]) == -1 then
                return redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return 0
            """, Long.class);

    public static void ensure(StringRedisTemplate redis, String key, Duration ttl) {
        redis.execute(EXPIRE_IF_PERSISTENT, List.of(key), Long.toString(ttl.toMillis()));
    }

    private RedisCacheExpiry() { }
}
