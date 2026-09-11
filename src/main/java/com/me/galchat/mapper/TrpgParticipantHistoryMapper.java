package com.me.galchat.mapper;

import com.me.galchat.domain.vo.TrpgParticipantHistoryVO;
import com.me.galchat.domain.vo.TrpgParticipantRunVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

public interface TrpgParticipantHistoryMapper {
    @Select("SELECT EXISTS (SELECT 1 FROM user_character_info WHERE user_world_id = #{userWorldId} AND character_id = #{characterId})")
    boolean characterExists(@Param("userWorldId") Long userWorldId, @Param("characterId") Long characterId);

    List<TrpgParticipantHistoryVO> selectSummaries(@Param("userWorldId") Long userWorldId);

    List<TrpgParticipantRunVO> selectCompletedRuns(@Param("userWorldId") Long userWorldId,
            @Param("characterId") Long characterId,
            @Param("beforeCompletedAt") LocalDateTime beforeCompletedAt,
            @Param("beforeConversationId") Long beforeConversationId,
            @Param("limit") int limit);
}
