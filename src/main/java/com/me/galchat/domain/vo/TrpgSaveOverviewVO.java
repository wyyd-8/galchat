package com.me.galchat.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
public class TrpgSaveOverviewVO {

    private Long id;
    private Long conversationId;
    private String conversationTitle;
    private String remark;
    private LocalDateTime savedAt;
    private Integer formatVersion;
    private String activePlanSource;
    private Long activeSceneId;
    private List<InvestigatorStateVO> investigators;

    @Data
    @Accessors(chain = true)
    public static class InvestigatorStateVO {
        private Long characterId;
        private String name;
        private Integer hpCurrent;
        private Integer hpMax;
        private Integer sanCurrent;
        private Integer sanMax;
        private Integer mpCurrent;
        private Integer mpMax;
        private Boolean unconscious;
        private Boolean dying;
        private Boolean dead;
    }
}
