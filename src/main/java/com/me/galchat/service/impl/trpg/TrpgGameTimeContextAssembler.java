package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.vo.TrpgGameTimeVO;
import com.me.galchat.mapper.GroupConversationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TrpgGameTimeContextAssembler {

    private final GroupConversationMapper conversationMapper;

    public String format(Long conversationId) {
        GroupConversation conversation = conversationId == null
                ? null
                : conversationMapper.selectById(conversationId);
        TrpgGameTimeVO time = TrpgGameTimeVO.from(conversation);
        if (time == null) {
            return "<current-game-time initialized=\"false\">尚未设定</current-game-time>";
        }
        return "<current-game-time day=\"" + time.dayNo()
                + "\" period=\"" + time.period() + "\">"
                + time.displayText()
                + "</current-game-time>";
    }
}
