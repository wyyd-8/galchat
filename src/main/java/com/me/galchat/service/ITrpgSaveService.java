package com.me.galchat.service;

import com.me.galchat.domain.dto.TrpgSaveCreateDTO;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.TrpgSaveOverviewVO;
import com.me.galchat.domain.vo.TrpgRollbackOverviewVO;
import com.me.galchat.domain.vo.TrpgRollbackResultVO;

public interface ITrpgSaveService {

    TrpgSaveOverviewVO getSave(Long userId, Long conversationId);

    TrpgSaveOverviewVO save(Long userId, Long conversationId, TrpgSaveCreateDTO createDTO);

    void load(Long userId, Long conversationId);

    void saveBeforeTurn(GroupConversation conversation);

    TrpgRollbackOverviewVO getRollbackOverview(
            Long userId, Long conversationId);

    TrpgRollbackResultVO rollbackTurn(Long userId, Long conversationId);

    TrpgRollbackResultVO rollbackScene(Long userId, Long conversationId);

    TrpgRollbackResultVO rollbackInitial(Long userId, Long conversationId);
}
