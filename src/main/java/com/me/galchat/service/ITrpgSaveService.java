package com.me.galchat.service;

import com.me.galchat.domain.dto.TrpgSaveCreateDTO;
import com.me.galchat.domain.vo.TrpgSaveOverviewVO;

public interface ITrpgSaveService {

    TrpgSaveOverviewVO getSave(Long userId, Long conversationId);

    TrpgSaveOverviewVO save(Long userId, Long conversationId, TrpgSaveCreateDTO createDTO);

    void load(Long userId, Long conversationId);
}
