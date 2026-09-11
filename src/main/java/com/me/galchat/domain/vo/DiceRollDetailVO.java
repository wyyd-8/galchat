package com.me.galchat.domain.vo;

import com.me.galchat.domain.po.DiceRollResult;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class DiceRollDetailVO {
    private Long id;
    private Long summaryId;
    private Long characterId;
    private Integer roundNo;
    private Integer displayOrder;
    private String displayType;
    private String reason;
    private DiceRollResultVO resultData;
    private DiceResolutionVO resolution;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DiceRollDetailVO from(DiceRollResult result) {
        return new DiceRollDetailVO()
                .setId(result.getId())
                .setSummaryId(result.getSummaryId())
                .setCharacterId(result.getCharacterId())
                .setRoundNo(result.getRoundNo())
                .setDisplayOrder(result.getDisplayOrder())
                .setDisplayType(result.getDisplayType())
                .setReason(result.getReason())
                .setResultData(result.getResultData())
                .setResolution(result.getResolutionData() == null
                        ? null
                        : result.getResolutionData().publicView())
                .setResolvedAt(result.getResolvedAt())
                .setCreatedAt(result.getCreatedAt())
                .setUpdatedAt(result.getUpdatedAt());
    }
}
