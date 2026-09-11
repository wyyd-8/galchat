package com.me.galchat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface VectorStoreCleanupMapper {

    int deleteWorldDetailsByWorldId(@Param("worldId") Long worldId);

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

    int deleteTrpgTurnsByConversation(
            @Param("conversationId") Long conversationId);

    int deleteTrpgTurnsByConversationAfterTurnId(
            @Param("conversationId") Long conversationId,
            @Param("maxTurnId") Long maxTurnId);

    int deleteTrpgTurnsByConversationAndTurnIds(
            @Param("conversationId") Long conversationId,
            @Param("turnIds") List<Long> turnIds);

}
