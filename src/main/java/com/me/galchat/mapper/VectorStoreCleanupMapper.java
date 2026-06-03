package com.me.galchat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VectorStoreCleanupMapper {

    int deleteChatHistoryByConversation(@Param("userWorldId") Long userWorldId,
                                        @Param("characterId") Long characterId);

    int deleteChatHistoryByConversationAfterEnd(@Param("userWorldId") Long userWorldId,
                                                @Param("characterId") Long characterId,
                                                @Param("thresholdEndMessageId") Long thresholdEndMessageId);

    int deleteWorldEventByUserWorldId(@Param("userWorldId") Long userWorldId);

    int deleteWorldEventByUserWorldIdAfterLogId(@Param("userWorldId") Long userWorldId,
                                                @Param("worldEventLogId") Long worldEventLogId);

    int deleteWorldEventByLogId(@Param("userWorldId") Long userWorldId,
                                @Param("worldEventLogId") Long worldEventLogId);
}
