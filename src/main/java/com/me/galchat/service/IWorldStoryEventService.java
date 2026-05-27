package com.me.galchat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.me.galchat.domain.dto.WorldStoryEventAdvanceDTO;
import com.me.galchat.domain.dto.WorldStoryEventEndDTO;
import com.me.galchat.domain.dto.WorldStoryEventStartDTO;
import com.me.galchat.domain.po.WorldStoryEvent;
import com.me.galchat.domain.vo.WorldStoryEventDetailVO;
import com.me.galchat.domain.vo.WorldStoryEventListVO;
import com.me.galchat.domain.vo.WorldStoryEventStartVO;

import java.util.List;

public interface IWorldStoryEventService extends IService<WorldStoryEvent> {

    List<WorldStoryEventListVO> listStories(Long userWorldId);

    WorldStoryEventDetailVO getStoryDetail(Long storyEventId);

    void startStory(WorldStoryEventStartDTO startDTO);

    WorldStoryEventStartVO getActiveStory(Long userWorldId);

    void advanceStory(Long storyEventId, WorldStoryEventAdvanceDTO advanceDTO);

    void endStory(Long storyEventId, WorldStoryEventEndDTO endDTO);
}
