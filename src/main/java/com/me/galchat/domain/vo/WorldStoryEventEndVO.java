package com.me.galchat.domain.vo;

import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.WorldEventLog;
import com.me.galchat.domain.po.WorldStoryEvent;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class WorldStoryEventEndVO {
    private WorldStoryEvent storyEvent;
    private WorldEventLog worldEventLog;
    private List<UserChatHistory> messages;
}
