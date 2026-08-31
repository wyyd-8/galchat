package com.me.galchat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GroupConversationDeletionMapper {

    int deleteConversationData(
            @Param("conversationId") Long conversationId);
}
