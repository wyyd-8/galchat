package com.me.galchat.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
public class TrpgRollbackOverviewVO {

    private RollbackPointVO turn;
    private RollbackPointVO scene;
    private RollbackPointVO initial;

    @Data
    @Accessors(chain = true)
    public static class RollbackPointVO {
        private Boolean available;
        private LocalDateTime savedAt;
        private Long messageBoundaryId;
        private Boolean willDeleteManualSave;
        private List<TrpgSaveOverviewVO.InvestigatorStateVO> investigators;
    }
}
