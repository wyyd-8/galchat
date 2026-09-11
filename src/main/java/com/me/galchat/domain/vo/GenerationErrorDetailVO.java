package com.me.galchat.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GenerationErrorDetailVO {
    private String errorId;
    private String code;
    private String category;
    private String message;
    private Boolean retryable;
    private String occurredAt;
    private String operation;
    private Map<String, Object> request;
    private Map<String, Object> response;
    private String stack;
}
