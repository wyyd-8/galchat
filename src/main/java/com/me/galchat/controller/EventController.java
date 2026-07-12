package com.me.galchat.controller;


import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.WorldStoryEventAdvanceDTO;
import com.me.galchat.domain.dto.WorldStoryEventEndDTO;
import com.me.galchat.domain.dto.WorldStoryEventStartDTO;
import com.me.galchat.service.IWorldStoryEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
@RestController
@RequestMapping("/worldevent")
@RequiredArgsConstructor
public class EventController {

    private final IWorldStoryEventService worldStoryEventService;

    @GetMapping("/story/list/{userWorldId}")
    public Result listStories(@PathVariable Long userWorldId) {
        return Result.success(worldStoryEventService.listStories(userWorldId));
    }

    @GetMapping("/story/{storyEventId}")
    public Result getStoryDetail(@PathVariable Long storyEventId) {
        return Result.success(worldStoryEventService.getStoryDetail(storyEventId));
    }

    @PostMapping("/story/start")
    public Result startStory(@RequestBody WorldStoryEventStartDTO startDTO) {
        return Result.success(worldStoryEventService.startStory(startDTO));
    }

    @GetMapping("/story/active/{userWorldId}")
    public Result getActiveStory(@PathVariable Long userWorldId) {
        return Result.success(worldStoryEventService.getActiveStory(userWorldId));
    }

    @PostMapping("/story/{storyEventId}/advance")
    public Result advanceStory(@PathVariable Long storyEventId,
                               @RequestBody WorldStoryEventAdvanceDTO advanceDTO) {
        worldStoryEventService.advanceStory(storyEventId, advanceDTO);
        return Result.success();
    }

    @PostMapping("/story/{storyEventId}/end")
    public Result endStory(@PathVariable Long storyEventId,
                           @RequestBody(required = false) WorldStoryEventEndDTO endDTO) {
        worldStoryEventService.endStory(storyEventId, endDTO);
        return Result.success();
    }
}
