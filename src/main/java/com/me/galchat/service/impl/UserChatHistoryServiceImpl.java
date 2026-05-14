package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.service.IUserChatHistoryService;
import com.me.galchat.service.IUserWorldPrefixService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
@Service
@RequiredArgsConstructor
public class UserChatHistoryServiceImpl extends ServiceImpl<UserChatHistoryMapper, UserChatHistory> implements IUserChatHistoryService {

    private final IUserWorldPrefixService userWorldPrefixService;

    @Override
    public List<UserChatHistory> listHistory(Long userWorldId, Long characterId, Long id, Integer size) {
        userWorldPrefixService.checkUserWorldAuth(userWorldId);
        if (characterId == null) {
            throw new UserRequestException("角色id不能为空");
        }

        List<UserChatHistory> histories = lambdaQuery()
                .eq(UserChatHistory::getUserWorldId, userWorldId)
                .eq(UserChatHistory::getCharacterId, characterId)
                .lt(id != null, UserChatHistory::getId, id)
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .notIn(UserChatHistory::getType, "system", "tool", "tool_call"))
                .orderByDesc(UserChatHistory::getId)
                .last("limit " + size)
                .list();
        Collections.reverse(histories);
        return histories;
    }
}
