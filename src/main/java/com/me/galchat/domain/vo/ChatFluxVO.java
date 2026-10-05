package com.me.galchat.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatFluxVO {
    private String type;
    private String content;
    private Long sequence;
    private GenerationErrorDetailVO errorDetail;

    public ChatFluxVO(String type, String content, Long sequence) {
        this(type, content, sequence, null);
    }

    public ChatFluxVO(String type, String content) {
        this(type, content, null);
    }
}
