package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class DiceResolutionVO {
    private String type;
    private Long sourceResultId;
    private String groupRule;
    private Map<String, Object> outcome;
    private Map<String, Object> effect;
}
