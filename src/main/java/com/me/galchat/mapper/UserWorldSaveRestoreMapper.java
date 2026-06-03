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

    int deleteStoryEventCharactersAfter(@Param("userWorldId") Long userWorldId,
                                        @Param("maxStoryEventCharacterId") Long maxStoryEventCharacterId);

    int deleteStoryEventsAfter(@Param("userWorldId") Long userWorldId, @Param("maxStoryEventId") Long maxStoryEventId);

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
