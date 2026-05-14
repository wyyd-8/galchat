package com.me.galchat.service;

import com.me.galchat.domain.po.UserChatHistory;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
public interface IUserChatHistoryService extends IService<UserChatHistory> {

    List<UserChatHistory> listHistory(Long userWorldId, Long characterId, Long id, Integer size);

}
