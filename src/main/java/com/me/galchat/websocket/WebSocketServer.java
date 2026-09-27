package com.me.galchat.websocket;

import com.me.galchat.config.WebSocketHandshakeConfigurator;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.websocket.*;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已持久化主动关怀消息的通知通道；聊天请求统一使用 HTTP 流式接口。
 */
@Component
@ServerEndpoint(value = "/ws/{sid}", configurator = WebSocketHandshakeConfigurator.class)
@Slf4j
@RequiredArgsConstructor
public class WebSocketServer {
    
    private final IUserWorldPrefixService userWorldService;
    private final JwtUtils jwtUtils;
    // 按用户世界保存所有活跃会话，同一用户多端连接时需要全部推送
    private static final Map<Long, Map<String, Session>> sessionMap = new ConcurrentHashMap<>();
    private static final Map<String, UserWorldPrefix> userWorldMap = new ConcurrentHashMap<>();
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
        Map<String, List<String>> requestParameterMap = session.getRequestParameterMap();

        // 从请求头或浏览器 WebSocket query 参数中获取 JWT
        String token = firstValue(request.getHeaders().get("token"));
        if (!StringUtils.hasText(token)) {
            token = firstValue(requestParameterMap.get("token"));
        }
        if (!StringUtils.hasText(token)) {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Missing token"));
            return;
        }

        // 解析用户身份
        String sid = session.getId();
        log.info("客户端：" + sid + "建立连接");
        Long id;
        try {
            Claims claims = jwtUtils.parseToken(token);
            id = Long.valueOf(String.valueOf(claims.get("id")));
            log.info("登录id:{}", id);
        } catch (Exception e) {
            log.info("不正确的token");
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Invalid token"));
            return;
        }

        // 从请求参数(Query String)中读取 userWorldId
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

    private String firstValue(List<String> values) {
        return values == null || values.isEmpty() ? null : values.getFirst();
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

    private void removeSessionGroupIfEmpty(Long userWorldId, Map<String, Session> sessions) {
        if (sessions.isEmpty()) {
            sessionMap.remove(userWorldId, sessions);
        }
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
