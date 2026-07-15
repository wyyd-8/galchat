package com.me.galchat.mapper;

import com.me.galchat.domain.po.UserCharacterFavorLog;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatThinkingHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.domain.po.WorldEventLog;
import org.apache.ibatis.annotations.Param;

public interface UserWorldSaveRestoreMapper {

    int deleteThinkingAfterChat(@Param("userWorldId") Long userWorldId, @Param("maxChatHistoryId") Long maxChatHistoryId);

    int deleteToolCallsAfterChat(@Param("userWorldId") Long userWorldId, @Param("maxChatHistoryId") Long maxChatHistoryId);

    int deleteChatAfter(@Param("userWorldId") Long userWorldId, @Param("maxChatHistoryId") Long maxChatHistoryId);

    int deleteFavorLogsAfter(@Param("userWorldId") Long userWorldId, @Param("maxFavorLogId") Long maxFavorLogId);

    int deleteUserEventsAfter(@Param("userWorldId") Long userWorldId, @Param("maxUserEventLogId") Long maxUserEventLogId);

    int deleteWorldEventsAfter(@Param("userWorldId") Long userWorldId, @Param("maxWorldEventLogId") Long maxWorldEventLogId);

    int deleteGroupMessagesAfter(@Param("userWorldId") Long userWorldId,
                                 @Param("maxGroupMessageId") Long maxGroupMessageId);

    int deleteGroupReplyStepsAfter(@Param("userWorldId") Long userWorldId,
                                   @Param("maxGroupReplyStepId") Long maxGroupReplyStepId);

    int deleteGroupTurnsAfter(@Param("userWorldId") Long userWorldId,
                              @Param("maxGroupTurnId") Long maxGroupTurnId);

    int deleteGroupSummariesAfter(@Param("userWorldId") Long userWorldId,
                                  @Param("maxGroupContextSummaryId") Long maxGroupContextSummaryId);

    int deleteReplyPlanItemsAfterConversation(@Param("userWorldId") Long userWorldId,
                                              @Param("maxGroupConversationId") Long maxGroupConversationId);

    int deleteReplyPlansAfterConversation(@Param("userWorldId") Long userWorldId,
                                          @Param("maxGroupConversationId") Long maxGroupConversationId);

    int deleteGroupMembersAfterConversation(@Param("userWorldId") Long userWorldId,
                                            @Param("maxGroupConversationId") Long maxGroupConversationId);

    int deleteGroupConversationsAfter(@Param("userWorldId") Long userWorldId,
                                      @Param("maxGroupConversationId") Long maxGroupConversationId);

    int deleteChatHistoryByIds(@Param("ids") Iterable<Long> ids);

    int deleteThinkingByUserMessageId(@Param("userMessageId") Long userMessageId);

    int deleteToolCallsByUserMessageId(@Param("userMessageId") Long userMessageId);

    int deleteFavorLogsByBindingChat(@Param("userWorldId") Long userWorldId,
                                     @Param("characterId") Long characterId,
                                     @Param("bindingChat") Long bindingChat);

    int insertChatHistoryWithId(@Param("history") UserChatHistory history);

    int insertThinkingWithId(@Param("thinking") UserChatThinkingHistory thinking);

    int insertToolCallWithId(@Param("toolCall") UserChatToolCall toolCall);

    int insertFavorLogWithId(@Param("favorLog") UserCharacterFavorLog favorLog);

    int upsertWorldEventLogWithId(@Param("worldEventLog") WorldEventLog worldEventLog);
}
