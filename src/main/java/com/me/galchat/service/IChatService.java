package com.me.galchat.service;

import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.domain.vo.ChatFluxVO;
import reactor.core.publisher.Flux;

public interface IChatService {

    Flux<ChatFluxVO> chat(ChatMessageDTO chatMessageDTO);
}
