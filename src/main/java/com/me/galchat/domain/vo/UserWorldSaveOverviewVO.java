package com.me.galchat.domain.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class UserWorldSaveOverviewVO {

    private Long userWorldId;

    private LocalDateTime savedAt;

    private String remark;

    private List<CharacterFavorVO> characterFavors;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    public static class CharacterFavorVO {
        private Long characterId;
        private String characterName;
        private Integer favorValue;
    }
}
