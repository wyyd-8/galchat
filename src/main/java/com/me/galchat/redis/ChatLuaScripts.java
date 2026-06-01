package com.me.galchat.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class ChatLuaScripts {

    private final RedisScript<String> updateFragmentScript = script("""
            local typingKey = KEYS[1]
            local inputKey = KEYS[2]
            local fragment = ARGV[1]
            local ttl = tonumber(ARGV[2]) or 3600
            local current = redis.call('GET', typingKey)
            local isTyping = 0
            local length = 0
            local revision = 0
            if current then
                local parts = {}
                for part in string.gmatch(current, '([^:]+)') do
                    table.insert(parts, part)
                end
                if #parts >= 3 then
                    isTyping = tonumber(parts[1]) or 0
                    length = tonumber(parts[2]) or 0
                    revision = tonumber(parts[3]) or 0
                end
            end
            local newLength = redis.call('APPEND', inputKey, fragment)
            local input = redis.call('GET', inputKey)
            local newRevision = revision + 1
            redis.call('SET', typingKey, tostring(isTyping) .. ':' .. tostring(newLength) .. ':' .. tostring(newRevision))
            redis.call('EXPIRE', typingKey, ttl)
            redis.call('EXPIRE', inputKey, ttl)
            return cjson.encode({
                state = tostring(isTyping) .. ':' .. tostring(newLength) .. ':' .. tostring(newRevision),
                input = input or "",
                length = newLength,
                revision = newRevision
            })
            """);

    private final RedisScript<String> updateTypingScript = script("""
            local typingKey = KEYS[1]
            local isTyping = tonumber(ARGV[1]) or 0
            local ttl = tonumber(ARGV[2]) or 3600
            local current = redis.call('GET', typingKey)
            local length = 0
            local revision = 0
            if current then
                local parts = {}
                for part in string.gmatch(current, '([^:]+)') do
                    table.insert(parts, part)
                end
                if #parts >= 3 then
                    length = tonumber(parts[2]) or 0
                    revision = tonumber(parts[3]) or 0
                end
            end
            redis.call('SET', typingKey, tostring(isTyping) .. ':' .. tostring(length) .. ':' .. tostring(revision))
            redis.call('EXPIRE', typingKey, ttl)
            return tostring(isTyping) .. ':' .. tostring(length) .. ':' .. tostring(revision)
            """);

    private final RedisScript<String> claimPendingScript = script("""
            local typingKey = KEYS[1]
            local inputKey = KEYS[2]
            local lastAssistantKey = KEYS[3]
            local expectedRevision = tonumber(ARGV[1])
            local expectedLength = tonumber(ARGV[2])
            local current = redis.call('GET', typingKey)
            if not current then
                return nil
            end
            local parts = {}
            for part in string.gmatch(current, '([^:]+)') do
                table.insert(parts, part)
            end
            if #parts < 3 then
                return nil
            end
            local isTyping = tonumber(parts[1]) or 0
            local length = tonumber(parts[2]) or 0
            local revision = tonumber(parts[3]) or 0
            if isTyping ~= 0 then
                return nil
            end
            if length ~= expectedLength or revision ~= expectedRevision then
                return nil
            end
            local input = redis.call('GET', inputKey)
            if not input then
                return nil
            end
            local lastAssistant = redis.call('GET', lastAssistantKey)
            redis.call('DEL', typingKey, inputKey)
            return cjson.encode({
                input = input,
                lastAssistant = lastAssistant or "",
                length = length,
                revision = revision
            })
            """);

    public RedisScript<String> updateFragmentScript() {
        return updateFragmentScript;
    }

    public RedisScript<String> updateTypingScript() {
        return updateTypingScript;
    }

    public RedisScript<String> claimPendingScript() {
        return claimPendingScript;
    }

    private static RedisScript<String> script(String script) {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(String.class);
        return redisScript;
    }
}
