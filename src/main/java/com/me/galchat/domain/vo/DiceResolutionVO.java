package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
public class DiceResolutionVO {
    private String type;
    private Long sourceResultId;
    private String groupRule;
    private String characterName;
    private String checkName;
    private String difficulty;
    private Integer targetValue;
    private List<Map<String, Object>> modifierFactors;
    private Map<String, Object> outcome;
    private Map<String, Object> effect;
}
