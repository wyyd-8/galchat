package com.me.galchat.config;

import com.me.galchat.constant.RedisConstant;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
@SuppressWarnings("removal")
public class RedisConfig {

    @Bean
    public RedisCacheConfiguration cacheConfiguration(CacheProperties properties) {
        // 使用无参构造，内部已经安全地开启了 DefaultTyping
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer();

        var ttl = properties.getRedis().getTimeToLive();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) ttl = RedisConstant.TEMPLATE_CACHE_TTL;
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                jsonSerializer));
    }
}
