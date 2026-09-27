package com.me.galchat.domain.vo;

import com.me.galchat.domain.po.UserChatHistory;
import java.util.List;

public record CareMessagePage(List<UserChatHistory> messages, long nextCursor, boolean hasMore) {
}
