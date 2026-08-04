package com.me.galchat.service;

import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;

import java.util.List;

public interface ITrpgRedisStateService {

    TrpgSaveSnapshotDTO.RedisStateSnapshot capture(
            Long conversationId, List<Long> sceneIds);

    void restore(
            Long conversationId,
            TrpgSaveSnapshotDTO.RedisStateSnapshot snapshot);
}
