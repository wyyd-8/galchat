package com.me.galchat.controller;

import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.domain.vo.ChatFluxVO;
import com.me.galchat.service.IChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import com.me.galchat.service.impl.chat.SingleChatGenerationRegistry;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.utils.CurrentHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/ai")
@Slf4j
@RequiredArgsConstructor
public class ChatController {

    private final IChatService chatService;
    private final SingleChatGenerationRegistry generations;
    private final IUserWorldPrefixService worlds;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatFluxVO> chat(@RequestBody ChatMessageDTO chatMessageDTO) {
        log.info("Received message: {}", chatMessageDTO == null ? null : chatMessageDTO.getMessage());
        // chat() validates ownership on the request thread, including for duplicate starts.
        Flux<ChatFluxVO> source = chatService.chat(chatMessageDTO);
        return generations.start(CurrentHolder.getCurrentId(), chatMessageDTO, source);
    }
    @GetMapping(value = "/chat/{userWorldId}/{characterId}/generations/{requestId}",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatFluxVO> resume(@PathVariable Long userWorldId, @PathVariable Long characterId,
                                   @PathVariable String requestId, @RequestParam(defaultValue = "0") long after) {
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) throw new UserAuthException("用户未登录");
        if (userWorldId <= 0 || characterId <= 0) throw new UserRequestException("单聊标识无效");
        worlds.checkUserWorldAuth(userId.longValue(), userWorldId, true);
        return generations.resume(userId, userWorldId, characterId, requestId, after);
    }
}
