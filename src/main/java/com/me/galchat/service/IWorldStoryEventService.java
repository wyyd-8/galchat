package com.me.galchat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.me.galchat.domain.dto.WorldStoryEventAdvanceDTO;
import com.me.galchat.domain.dto.WorldStoryEventEndDTO;
import com.me.galchat.domain.dto.WorldStoryEventStartDTO;
import com.me.galchat.domain.po.WorldStoryEvent;
import com.me.galchat.domain.vo.WorldStoryEventAdvanceVO;
import com.me.galchat.domain.vo.WorldStoryEventEndVO;
import com.me.galchat.domain.vo.WorldStoryEventStartVO;

public interface IWorldStoryEventService extends IService<WorldStoryEvent> {

    WorldStoryEventStartVO startStory(WorldStoryEventStartDTO startDTO);

    WorldStoryEventStartVO getActiveStory(Long userWorldId);

    WorldStoryEventAdvanceVO advanceStory(Long storyEventId, WorldStoryEventAdvanceDTO advanceDTO);

    WorldStoryEventEndVO endStory(Long storyEventId, WorldStoryEventEndDTO endDTO);
}
