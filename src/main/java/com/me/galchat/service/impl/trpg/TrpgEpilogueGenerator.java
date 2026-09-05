package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.dto.TrpgEpilogueModels;
import com.me.galchat.domain.po.GroupConversation;
import java.util.List;

public interface TrpgEpilogueGenerator {

    TrpgEpilogueModels.Response generate(
            GroupConversation conversation,
            List<TrpgEpilogueModels.Subject> subjects,
            String publicHistory);
}
