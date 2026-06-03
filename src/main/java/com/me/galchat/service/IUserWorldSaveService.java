package com.me.galchat.service;

import com.me.galchat.domain.dto.UserWorldSaveCreateDTO;
import com.me.galchat.domain.vo.UserWorldSaveOverviewVO;

public interface IUserWorldSaveService {

    UserWorldSaveOverviewVO getSave(Long userId, Long userWorldId);

    UserWorldSaveOverviewVO saveWorld(Long userId, Long userWorldId, UserWorldSaveCreateDTO createDTO);

    void loadWorld(Long userId, Long userWorldId);
}
