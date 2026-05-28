package com.me.galchat.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class UserEventLogDelayTaskDTO {

    private String taskType;

    private Long userWorldId;

    private Long characterId;

    private List<Long> userEventLogIds;

    private Integer retryCount;
}
