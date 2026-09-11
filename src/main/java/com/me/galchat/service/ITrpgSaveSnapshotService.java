package com.me.galchat.service;

import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.GroupConversation;

public interface ITrpgSaveSnapshotService {

    TrpgSaveSnapshotDTO capture(GroupConversation conversation);

    void restoreDatabase(GroupConversation conversation, TrpgSaveSnapshotDTO snapshot);

    void restoreDerivedState(GroupConversation conversation, TrpgSaveSnapshotDTO snapshot);
}
