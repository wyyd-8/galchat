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

    int deleteGroupTopicsAfterId(@Param("userWorldId") Long userWorldId,
                                 @Param("maxGroupTopicId") Long maxGroupTopicId);

    int deleteGroupTopicsByConversation(@Param("conversationId") Long conversationId);

    int deleteGroupTopicsByConversationAfterEnd(@Param("conversationId") Long conversationId,
                                                @Param("thresholdEndSequence") Long thresholdEndSequence);

    int deleteGroupTopicsByConversationAfterTopicId(
            @Param("conversationId") Long conversationId,
            @Param("maxGroupTopicId") Long maxGroupTopicId);

    int deleteWorldEventByUserWorldId(@Param("userWorldId") Long userWorldId);

    int deleteWorldEventByUserWorldIdAfterLogId(@Param("userWorldId") Long userWorldId,
                                                @Param("worldEventLogId") Long worldEventLogId);

    int deleteWorldEventByLogId(@Param("userWorldId") Long userWorldId,
                                @Param("worldEventLogId") Long worldEventLogId);

    int deleteWorldEventByConversationAfterLogId(
            @Param("conversationId") Long conversationId,
            @Param("maxWorldEventLogId") Long maxWorldEventLogId);
}
