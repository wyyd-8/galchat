package com.me.galchat.controller;

import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.domain.vo.ChatFluxVO;
import com.me.galchat.service.IChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
@Slf4j
@RequiredArgsConstructor
public class ChatController {

    private final IChatService chatService;

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatFluxVO> chat(@RequestBody ChatMessageDTO chatMessageDTO) {
        log.info("Received message: {}", chatMessageDTO == null ? null : chatMessageDTO.getMessage());
        return chatService.chat(chatMessageDTO);
    }
}
