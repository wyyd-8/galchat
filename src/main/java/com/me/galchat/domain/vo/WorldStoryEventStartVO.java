package com.me.galchat.domain.vo;

import com.me.galchat.domain.po.WorldStoryEvent;
import com.me.galchat.domain.po.WorldStoryEventCharacter;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class WorldStoryEventStartVO {
    private WorldStoryEvent storyEvent;
    private List<WorldStoryEventCharacter> characters;
}
