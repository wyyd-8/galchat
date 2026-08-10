package com.me.galchat.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
public class DiceResolutionDataVO {
    private Integer version = 1;
    private String type;
    private Long sourceResultId;
    private Map<String, Object> rule = new LinkedHashMap<>();
    private Map<String, Object> outcome;
    private Map<String, Object> effect;

    public static DiceResolutionDataVO pending(
            String type, Long sourceResultId, Map<String, Object> rule) {
        return new DiceResolutionDataVO()
                .setType(type)
                .setSourceResultId(sourceResultId)
                .setRule(new LinkedHashMap<>(rule));
    }

    public DiceResolutionVO publicView() {
        Object savedGroupRule = rule == null ? null : rule.get("groupRule");
        return new DiceResolutionVO(
                type,
                sourceResultId,
                savedGroupRule instanceof String value ? value : null,
                outcome,
                effect);
    }
}
