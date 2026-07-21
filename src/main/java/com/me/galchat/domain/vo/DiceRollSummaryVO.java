package com.me.galchat.domain.vo;

import com.me.galchat.domain.po.DiceRollSummary;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class DiceRollSummaryVO {
    private Long id;
    private Long conversationId;
    private String reason;
    private String totalResult;
    private Integer roundCount;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DiceRollSummaryVO from(DiceRollSummary summary) {
        return new DiceRollSummaryVO()
                .setId(summary.getId())
                .setConversationId(summary.getConversationId())
                .setReason(summary.getReason())
                .setTotalResult(summary.getTotalResult())
                .setRoundCount(summary.getRoundCount())
                .setStatus(summary.getStatus())
                .setCreatedAt(summary.getCreatedAt())
                .setUpdatedAt(summary.getUpdatedAt());
    }
}
