package com.me.galchat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VectorStoreCleanupMapper {

    int deleteChatHistoryByConversation(@Param("userWorldId") Long userWorldId,
                                        @Param("characterId") Long characterId);

    int deleteWorldEventByUserWorldId(@Param("userWorldId") Long userWorldId);
}
