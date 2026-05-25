package com.me.galchat.websocket;

import com.me.galchat.consumer.ChatQueueNames;
import com.me.galchat.config.WebSocketHandshakeConfigurator;
import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.domain.dto.ChatReplyTaskDTO;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.redis.ChatLuaScripts;
import com.me.galchat.service.InputCompletionClassifier;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.websocket.*;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.redisson.api.*;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket服务
 */
@Component
@ServerEndpoint(value = "/ws/{sid}", configurator = WebSocketHandshakeConfigurator.class)
@Slf4j
@RequiredArgsConstructor
public class WebSocketServer {
    
    private final IUserWorldPrefixService userWorldService;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final ChatLuaScripts chatLuaScripts;
    private final InputCompletionClassifier inputCompletionClassifier;

    @Resource(name = "delayTaskExecutor")
    private ThreadPoolTaskExecutor delayTaskExecutor;
    @Resource(name = "bertTaskExecutor")
    private ThreadPoolTaskExecutor bertTaskExecutor;

    // 按用户世界保存所有活跃会话，同一用户多端连接时需要全部推送
    private static final Map<Long, Map<String, Session>> sessionMap = new ConcurrentHashMap<>();
    private static final Map<String, UserWorldPrefix> userWorldMap = new ConcurrentHashMap<>();
    private static RDelayedQueue<ChatMessageDTO> delayedQueue;
    private static RBlockingQueue<ChatMessageDTO> blockingQueue;
    private static RBlockingQueue<ChatReplyTaskDTO> replyQueue;

    private static final String TRIGGER_BERT = "bert";
    private static final String TRIGGER_FALLBACK = "fallback";
    private static final Duration BERT_TRIGGER_DELAY = Duration.ofMillis(700);
    private static final Duration FALLBACK_TRIGGER_DELAY = Duration.ofSeconds(3);
    private static final Duration INPUT_STATE_TTL = Duration.ofHours(1);
    private static volatile boolean begin = true;

    /**
     * 初始化延时队列并启动延时任务消费线程。
     */
    @PostConstruct
    public void init() {
        begin = true;

        // 初始化 Redisson 的阻塞队列和延时队列
        // 创建队列时指定 JsonJacksonCodec，只影响这个队列
        blockingQueue = redissonClient.getBlockingQueue(ChatQueueNames.DELAY_QUEUE_NAME, new JsonJacksonCodec());
        delayedQueue = redissonClient.getDelayedQueue(blockingQueue);//使用Json序列化，不添加是java默认的序列化
        replyQueue = redissonClient.getBlockingQueue(ChatQueueNames.REPLY_QUEUE_NAME, new JsonJacksonCodec());

        // 启动异步线程消费延时任务
        for (int i = 0; i < 4; i++) {
            delayTaskExecutor.execute(this::handleDelayTask);
        }
    }

    /**
     * 关闭延时任务消费并销毁延时队列。
     */
    @PreDestroy
    public void destroy() {
        stopWorkers();

        // 销毁 Redisson 可靠队列
        if (delayedQueue != null) {
            delayedQueue.destroy();
        }
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        stopWorkers();
    }

    private void stopWorkers() {
        begin = false;

        if (delayTaskExecutor != null) {
            delayTaskExecutor.shutdown();
        }
        if (bertTaskExecutor != null) {
            bertTaskExecutor.shutdown();
        }
    }

    /**
     * 循环消费延时队列中的到期聊天任务。
     */
    public void handleDelayTask() {
        while (begin) {
            try {
                // 从阻塞队列中获取到期的任务，定期醒来检查关闭信号
                ChatMessageDTO message = blockingQueue.poll(1, TimeUnit.SECONDS);
                if (message == null) {
                    continue;
                }

                handleTypingDelayTask(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (begin) {
                    log.error("处理延时任务异常", e);
                }
            }
        }
    }

    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session, EndpointConfig config) throws IOException {
        // 获取握手请求信息
        HandshakeRequest request = (HandshakeRequest) config.getUserProperties()
                .get(HandshakeRequest.class.getName());
        if (request == null) {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Missing request"));
            return;
        }

        // 从请求头中获取 JWT
        List<String> authHeaders = request.getHeaders().get("token");
        if (authHeaders == null || authHeaders.isEmpty()) {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Missing token"));
            return;
        }

        // 解析用户身份
        String sid = session.getId();
        log.info("客户端：" + sid + "建立连接");
        Long id;
        try {
            Claims claims = JwtUtils.parseToken(authHeaders.getFirst());
            id = Long.valueOf(String.valueOf(claims.get("id")));
            log.info("登录id:{}", id);
        } catch (Exception e) {
            log.info("不正确的token");
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Invalid token"));
            return;
        }

        // 从请求参数(Query String)中读取 userWorldId
        Map<String, List<String>> requestParameterMap = session.getRequestParameterMap();
        List<String> userWorldIdParams = requestParameterMap.get("userWorldId");
        if (userWorldIdParams == null || userWorldIdParams.isEmpty()) {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Missing param"));
            return;
        }
        Long userWorldId;
        try {
            userWorldId = Long.valueOf(userWorldIdParams.getFirst());
        } catch (NumberFormatException e) {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Invalid param"));
            return;
        }

        UserWorldPrefix prefix;
        try {
            prefix = userWorldService.checkUserWorldAuth(id, userWorldId, true);
        } catch (Exception e) {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Wrong param"));
            return;
        }

        // 缓存会话和用户世界关系
        sessionMap.computeIfAbsent(prefix.getId(), key -> new ConcurrentHashMap<>())
                .put(sid, session);
        userWorldMap.put(sid, prefix);
    }

    /**
     * 收到客户端消息后调用的方法
     *
     * @param message 客户端发送过来的消息
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        // 获取当前连接绑定的用户世界
        String sid = session.getId();
        UserWorldPrefix prefix = userWorldMap.get(sid);
        if (prefix == null) {
            log.warn("未找到会话绑定的用户世界信息, sid:{}", sid);
            return;
        }

        // 反序列化并补齐消息上下文
        ChatMessageDTO data = JSONObject.fromJson(message, ChatMessageDTO.class);
        data.setUserWorldId(prefix.getId());
        if (data.getWorldId() == null) {
            data.setWorldId(prefix.getWorldId());
        }

        // 按消息类型分发处理
        log.info("收到来自客户端：" + sid + "的信息:" + data);
        switch (data.getType()) {
            case "fragment" -> handleMessageFragment(data, sid, prefix.getEotDetectionStatus());
            case "typing" -> handleTypingStatus(data);
        }
    }

    /**
     * 处理客户端输入片段并更新 Redis 中的输入内容。
     */
    private void handleMessageFragment(ChatMessageDTO ctx, String senderSid, boolean enableEotDetection) {
        // 调用 Lua 脚本追加输入片段
        String snapshot = redisTemplate.execute(chatLuaScripts.updateFragmentScript(),
                List.of(buildTypingKey(ctx), buildChatKey(ctx)),
                ctx.getMessage(),
                String.valueOf(INPUT_STATE_TTL.toSeconds()));

        // 空结果表示无需后续处理
        if (snapshot == null || snapshot.isBlank()) {
            return;
        }
        InputSnapshot inputSnapshot = deserializeInputSnapshot(snapshot);
        ctx.setLength(inputSnapshot.length());
        ctx.setRevision(inputSnapshot.revision());
        broadcastMessageToOtherSessions(ctx, senderSid);
        if (enableEotDetection) {
            scheduleBertEarlyTrigger(ctx, inputSnapshot.input());
        }
    }

    /**
     * 处理打字状态 (核心暂停/恢复逻辑)
     */
    private void handleTypingStatus(ChatMessageDTO ctx) {
        // 更新当前会话的打字状态
        String state = redisTemplate.execute(chatLuaScripts.updateTypingScript(),
                List.of(buildTypingKey(ctx)),
                Boolean.TRUE.equals(ctx.getIsTyping()) ? "1" : "0",
                String.valueOf(INPUT_STATE_TTL.toSeconds()));
        if (state == null || state.isBlank()) {
            return;
        }
        TypingState typingState = deserializeTypingState(state);
        ctx.setLength(typingState.length());
        ctx.setRevision(typingState.revision());

        // 停止输入时注册保底触发和 BERT 提前触发
        if (!Boolean.TRUE.equals(ctx.getIsTyping()) && typingState.length() > 0) {
            addDelayTask(ctx, TRIGGER_FALLBACK, FALLBACK_TRIGGER_DELAY);
        }
    }

    /**
     * 处理已到期的输入延时任务并尝试认领待回复内容。
     */
    private void handleTypingDelayTask(ChatMessageDTO data) {
        // 认领当前版本的待回复输入
        ClaimedConversation claimedConversation = claimConversation(data);
        if (claimedConversation == null) {
            return;
        }

        log.info("触发回复, triggerType={}, revision={}, length={}",
                data.getTriggerType(), claimedConversation.revision(), claimedConversation.length());
        addReplyTask(data, claimedConversation);
    }

    /**
     * 向指定会话发送聊天记录消息
     */
    public void sendMessageToSession(UserChatHistory userChatHistory) {
        // 校验聊天记录和目标会话标识
        if (userChatHistory == null || userChatHistory.getUserWorldId() == null) {
            return;
        }

        // 查找并校验目标 WebSocket 会话
        Map<String, Session> sessions = sessionMap.get(userChatHistory.getUserWorldId());
        if (sessions == null || sessions.isEmpty()) {
            log.warn("目标会话不存在或已关闭, userWorldId:{}", userChatHistory.getUserWorldId());
            return;
        }

        // 异步推送聊天记录到当前用户世界下的所有连接
        String payload = new JSONObject(userChatHistory).toString();
        sessions.forEach((sid, session) -> {
            if (session == null || !session.isOpen()) {
                sessions.remove(sid, session);
                return;
            }
            session.getAsyncRemote().sendText(payload, result -> {
                if (!result.isOK()) {
                    log.warn("推送聊天记录失败, sid:{}, userWorldId:{}",
                            sid, userChatHistory.getUserWorldId(), result.getException());
                }
            });
        });
        removeSessionGroupIfEmpty(userChatHistory.getUserWorldId(), sessions);
    }

    /**
     * 将某个设备发来的输入/打字状态同步给同一用户世界下的其他连接。
     */
    private void broadcastMessageToOtherSessions(ChatMessageDTO message, String senderSid) {
        Map<String, Session> sessions = sessionMap.get(message.getUserWorldId());
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        String payload = new JSONObject(message).toString();
        sessions.forEach((sid, session) -> {
            if (sid.equals(senderSid)) {
                return;
            }
            if (session == null || !session.isOpen()) {
                sessions.remove(sid, session);
                return;
            }
            session.getAsyncRemote().sendText(payload, result -> {
                if (!result.isOK()) {
                    log.warn("同步WebSocket消息失败, sid:{}, userWorldId:{}",
                            sid, message.getUserWorldId(), result.getException());
                }
            });
        });
        removeSessionGroupIfEmpty(message.getUserWorldId(), sessions);
    }

    private void removeSessionGroupIfEmpty(Long userWorldId, Map<String, Session> sessions) {
        if (sessions.isEmpty()) {
            sessionMap.remove(userWorldId, sessions);
        }
    }

    /**
     * 构建当前会话的打字状态 Redis key。
     */
    private String buildTypingKey(ChatMessageDTO ctx) {
        return buildConversationKey(ctx) + ":typing";
    }

    /**
     * 构建当前会话的输入内容 Redis key。
     */
    private String buildChatKey(ChatMessageDTO ctx) {
        return buildConversationKey(ctx) + ":input";
    }

    /**
     * 构建当前会话的上一条助手回复 Redis key。
     */
    private String buildLastAssistantKey(ChatMessageDTO ctx) {
        return buildConversationKey(ctx) + ":last_assistant";
    }

    /**
     * 按用户世界和角色维度构建会话 Redis key 前缀。
     */
    private String buildConversationKey(ChatMessageDTO ctx) {
        // 优先使用用户世界主键构建会话维度
        Long userWorldId = ctx.getUserWorldId();
        if (userWorldId != null) {
            return "chat:" + userWorldId + ":" + ctx.getCharacterId();
        }

        // 兼容未携带用户世界主键的消息
        return "chat:" + ctx.getWorldId() + ":" + ctx.getCharacterId();
    }

    /**
     * 从 Redis 读取并解析当前打字状态。
     */
    private TypingState getTypingState(ChatMessageDTO ctx) {
        // 读取打字状态原始值
        String value = redisTemplate.opsForValue().get(buildTypingKey(ctx));
        if (value == null || value.isBlank()) {
            return new TypingState(false, 0, 0);
        }

        // 解析失败时回退到默认状态
        try {
            return deserializeTypingState(value);
        } catch (NumberFormatException e) {
            log.warn("typing状态不是合法值, key:{}, value:{}", buildTypingKey(ctx), value);
            return new TypingState(false, 0, 0);
        }
    }

    /**
     * 将 Redis 中的打字状态字符串转换为结构化状态。
     */
    private TypingState deserializeTypingState(String value) {
        // 空值视为默认未输入状态
        if (value == null || value.isBlank()) {
            return new TypingState(false, 0, 0);
        }

        // 兼容历史单值格式
        String[] parts = value.split(":");
        if (parts.length == 1) {
            long legacyValue = Long.parseLong(value);
            return new TypingState((legacyValue & 1L) == 1L, legacyValue >> 1, 0);
        }

        // 兼容带额外字段的状态格式
        if (parts.length == 4) {
            return new TypingState(
                    "1".equals(parts[0]),
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2])
            );
        }

        // 校验当前状态格式
        if (parts.length != 3) {
            throw new NumberFormatException("typing state parts length invalid");
        }

        // 解析当前状态格式
        return new TypingState(
                "1".equals(parts[0]),
                Long.parseLong(parts[1]),
                Long.parseLong(parts[2])
        );
    }

    /**
     * 将追加输入片段后的 Lua 返回值转换为输入快照。
     */
    private InputSnapshot deserializeInputSnapshot(String value) {
        JSONObject jsonObject = new JSONObject(value);
        TypingState typingState = deserializeTypingState(jsonObject.optString("state", ""));
        return new InputSnapshot(
                jsonObject.optString("input", ""),
                jsonObject.optLong("length", typingState.length()),
                jsonObject.optLong("revision", typingState.revision())
        );
    }

    /**
     * 创建聊天延时任务并写入 Redisson 可靠队列。
     */
    private void addDelayTask(ChatMessageDTO ctx, String triggerType, Duration delay) {
        // 拷贝触发任务需要的消息快照
        ChatMessageDTO task = new ChatMessageDTO();
        task.setType(ctx.getType());
        task.setWorldId(ctx.getWorldId());
        task.setUserWorldId(ctx.getUserWorldId());
        task.setCharacterId(ctx.getCharacterId());
        task.setLength(ctx.getLength());
        task.setRevision(ctx.getRevision());
        task.setTriggerType(triggerType);

        // 按指定延迟加入可靠队列
        delayedQueue.offer(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 将已认领的完整输入投递给聊天消费者。
     */
    private void addReplyTask(ChatMessageDTO ctx, ClaimedConversation claimedConversation) {
        Long worldId = ctx.getWorldId();
        Long characterId = ctx.getCharacterId();
        if (ctx.getUserWorldId() == null || worldId == null || characterId == null) {
            log.warn("回复任务缺少必要上下文, userWorldId:{}, worldId:{}, characterId:{}",
                    ctx.getUserWorldId(), ctx.getWorldId(), ctx.getCharacterId());
            return;
        }

        ChatReplyTaskDTO task = new ChatReplyTaskDTO();
        task.setWorldId(worldId);
        task.setUserWorldId(ctx.getUserWorldId());
        task.setCharacterId(characterId);
        task.setMessage(claimedConversation.input());
        task.setLength(claimedConversation.length());
        task.setRevision(claimedConversation.revision());
        task.setTriggerType(ctx.getTriggerType());
        replyQueue.offer(task);
    }

    /**
     * 异步判断输入是否完整，并在完整时注册 BERT 提前触发任务。
     */
    private void scheduleBertEarlyTrigger(ChatMessageDTO ctx, String accumulatedInput) {
        // 拷贝当前输入状态快照
        ChatMessageDTO snapshot = new ChatMessageDTO();
        snapshot.setType(ctx.getType());
        snapshot.setWorldId(ctx.getWorldId());
        snapshot.setUserWorldId(ctx.getUserWorldId());
        snapshot.setCharacterId(ctx.getCharacterId());
        snapshot.setLength(ctx.getLength());
        snapshot.setRevision(ctx.getRevision());

        // 在线程池中执行完整性判断
        bertTaskExecutor.execute(() -> {
            try {
                if (accumulatedInput == null || accumulatedInput.isBlank()) {
                    return;
                }
                TypingState typingState = getTypingState(snapshot);
                if (typingState.length() != snapshot.getLength()
                        || typingState.revision() != snapshot.getRevision()) {
                    return;
                }

                // 完整输入注册提前触发任务
                String lastAssistant = redisTemplate.opsForValue().get(buildLastAssistantKey(snapshot));
                if (inputCompletionClassifier.isComplete(accumulatedInput, lastAssistant)) {
                    addDelayTask(snapshot, TRIGGER_BERT, BERT_TRIGGER_DELAY);
                }
            } catch (Exception e) {
                log.warn("BERT提前触发任务异常，等待3秒保底任务", e);
            }
        });
    }

    /**
     * 通过 Lua 脚本认领指定版本和长度的待回复输入。
     */
    private ClaimedConversation claimConversation(ChatMessageDTO data) {
        // 原子认领当前待处理输入
        String claimed = redisTemplate.execute(chatLuaScripts.claimPendingScript(),
                List.of(buildTypingKey(data), buildChatKey(data), buildLastAssistantKey(data)),
                String.valueOf(data.getRevision()),
                String.valueOf(data.getLength()),
                data.getTriggerType());
        if (claimed == null || claimed.isBlank()) {
            return null;
        }

        // 转换认领结果为结构化数据
        JSONObject jsonObject = new JSONObject(claimed);
        return new ClaimedConversation(
                jsonObject.optString("input", ""),
                jsonObject.optString("lastAssistant", ""),
                jsonObject.optLong("length", 0),
                jsonObject.optLong("revision", 0)
        );
    }

    private record TypingState(boolean isTyping, long length, long revision) {
    }

    private record InputSnapshot(String input, long length, long revision) {
    }

    private record ClaimedConversation(String input, String lastAssistant, long length, long revision) {
    }

    /**
     * 连接关闭调用的方法
     *
     * @param session 会话对象
     */
    @OnClose
    public void onClose(Session session) {
        // 获取并记录关闭的连接
        String sid = session.getId();
        log.info("连接断开:" + sid);

        // 清理连接和用户世界映射
        UserWorldPrefix prefix = userWorldMap.remove(sid);
        if (prefix != null) {
            sessionMap.computeIfPresent(prefix.getId(), (userWorldId, sessions) -> {
                sessions.remove(sid, session);
                return sessions.isEmpty() ? null : sessions;
            });
        }
    }
}
