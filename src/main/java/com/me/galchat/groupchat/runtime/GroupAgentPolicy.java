package com.me.galchat.groupchat.runtime;

import com.me.galchat.domain.po.GroupConversation;

public interface GroupAgentPolicy {

    GroupModelInvocation prepare(GroupConversation conversation, GroupActionSpec action,
                                 GroupContextMaterial context);

    String characterName(Long userWorldId, Long characterId);
}
