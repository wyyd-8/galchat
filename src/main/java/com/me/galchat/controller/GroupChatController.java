package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.GroupChatRequestDTO;
import com.me.galchat.domain.dto.GroupConversationCreateDTO;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.service.impl.GroupChatService;
import com.me.galchat.service.impl.GroupConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/group-chat")
@RequiredArgsConstructor
public class GroupChatController {

    private final GroupConversationService conversationService;
    private final GroupChatService groupChatService;

    @PostMapping("/conversations")
    public Result createConversation(@RequestBody GroupConversationCreateDTO dto) {
        return Result.success(conversationService.create(dto));
    }

    @PostMapping(value = "/conversations/{conversationId}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GroupChatEvent> chat(@PathVariable Long conversationId,
                                     @RequestBody GroupChatRequestDTO request) {
        return groupChatService.chat(conversationId, request);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Result listHistory(@PathVariable Long conversationId,
                              @RequestParam(required = false) Long beforeId,
                              @RequestParam(required = false) Integer size) {
        return Result.success(groupChatService.listHistory(conversationId, beforeId, size));
    }
}
