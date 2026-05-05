package com.me.galchat.websocket;

import com.me.galchat.domain.dto.ChatMessage;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.websocket.*;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RReliableQueue;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * WebSocket服务
 */
@Component
@ServerEndpoint("/ws/{sid}")
@Slf4j
public class WebSocketServer {
    @Autowired
    IUserWorldPrefixService userWorldService;
    @Autowired
    StringRedisTemplate redisTemplate;
    @Autowired
    RedissonClient redissonClient;
    @Resource(name = "delayTaskExecutor")
    private Executor delayTaskExecutor;

    //存放会话对象
    private static final Map<Long, Session> sessionMap = new ConcurrentHashMap<>();
    private static final Map<String, UserWorldPrefix> userWorldMap = new ConcurrentHashMap<>();
    private static RBlockingQueue<ChatMessage> blockingQueue;
    private static RReliableQueue<ChatMessage> delayedQueue;

    private static final String DELAY_QUEUE_NAME = "chat:delay:queue";
    private static volatile boolean begin = true;

    @PostConstruct
    public void init() {
        // 初始化 Redisson 的阻塞队列和延时队列
        // 创建队列时指定 JsonJacksonCodec，只影响这个队列
        blockingQueue = redissonClient.getBlockingQueue(
                DELAY_QUEUE_NAME, new JsonJacksonCodec());//使用Json序列化，不添加是java默认的序列化
        delayedQueue = redissonClient.getReliableQueue(DELAY_QUEUE_NAME);
        // 启动异步线程消费延时任务
        for (int i = 0; i < 4; i++) {
            delayTaskExecutor.execute(this::handleDelayTask);
        }
    }

    @PreDestroy
    public void destroy() {
        begin = false;
        delayedQueue.destroy();
    }

    public void handleDelayTask() {
        while (begin) {
            try {
                // 从阻塞队列中获取到期的任务
                ChatMessage data = blockingQueue.take();
            } catch (Exception e) {
                log.error("处理延时任务异常", e);
            }
        }
    }

    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
        HandshakeRequest request = (HandshakeRequest) config.getUserProperties()
                .get(HandshakeRequest.class.getName());
        if (request == null) {
            return;
        }
        // 或者从请求头中获取 JWT
        List<String> authHeaders = request.getHeaders().get("token");
        // ...
        if (authHeaders == null || authHeaders.isEmpty()) {
            return;
        }
        String sid = session.getId();
        System.out.println("客户端：" + sid + "建立连接");
        Claims claims = JwtUtils.parseToken(authHeaders.getFirst());
        Long id = (Long) claims.get("id");
        log.info("登录id:{}", id);

        // 从请求参数(Query String)中读取 worldId
        Map<String, List<String>> requestParameterMap = session.getRequestParameterMap();
        List<String> worldIdParams = requestParameterMap.get("worldId");
        if (worldIdParams == null || worldIdParams.isEmpty()) {
            return;
        }
        Long worldId = Long.valueOf(worldIdParams.getFirst());

        UserWorldPrefix prefix = userWorldService.getByUserIdAndWorldId(id, worldId);
        if (prefix == null) {
            return;
        }
        sessionMap.put(prefix.getId(), session);
        userWorldMap.put(sid, prefix);
    }

    /**
     * 收到客户端消息后调用的方法
     *
     * @param message 客户端发送过来的消息
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        String sid = session.getId();
        ChatMessage data = JSONObject.fromJson(message, ChatMessage.class);
        System.out.println("收到来自客户端：" + sid + "的信息:" + data);
        switch (data.getType()) {
            case "fragment" -> redisTemplate.opsForValue().append("chat:" + data.getWorldId() + ":" + data.getCharacterId(), data.getMessage());
            case "typing" -> handleTypingStatus(data, sid);
        }
    }

    /**
     * 处理打字状态 (核心暂停/恢复逻辑)
     */
    private void handleTypingStatus(ChatMessage ctx, String sessionId) {

    }

    /**
     * 连接关闭调用的方法
     *
     * @param session 会话对象
     */
    @OnClose
    public void onClose(Session session) {
        String sid = session.getId();
        System.out.println("连接断开:" + sid);
        sessionMap.remove(userWorldMap.remove(sid).getId());
    }
}
