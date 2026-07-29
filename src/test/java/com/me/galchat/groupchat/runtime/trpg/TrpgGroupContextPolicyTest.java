package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.service.impl.GroupContextAssembler;
import com.me.galchat.service.impl.TrpgModuleContextAssembler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgGroupContextPolicyTest {

    @Test
    void onlyKpReceivesPrivateModuleContext() {
        GroupContextAssembler groupAssembler =
                mock(GroupContextAssembler.class);
        TrpgModuleContextAssembler moduleAssembler =
                mock(TrpgModuleContextAssembler.class);
        TrpgGroupContextPolicy policy = new TrpgGroupContextPolicy(
                groupAssembler, moduleAssembler);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setModuleId(3L);
        when(groupAssembler.assembleContext(
                any(), any(), any())).thenReturn(List.of());
        when(moduleAssembler.formatKpContext(conversation))
                .thenReturn("<module-global-context>幕后真相</module-global-context>");

        var kpContext = policy.load(conversation, action(
                GroupChatConstant.ACTOR_KP, null));
        var investigatorContext = policy.load(conversation, action(
                GroupChatConstant.ACTOR_CHARACTER, 9L));

        assertThat(kpContext.messages())
                .extracting(message -> message.getText())
                .anyMatch(text -> text.contains("幕后真相"));
        assertThat(investigatorContext.messages())
                .extracting(message -> message.getText())
                .noneMatch(text -> text.contains("幕后真相"));
    }

    private GroupActionSpec action(String actorType, Long actorId) {
        return new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                actorType,
                actorId,
                "scene:21",
                "医院",
                1,
                1);
    }
}
