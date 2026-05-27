package com.me.galchat.service;

import com.me.galchat.domain.po.UserWorldPrefix;
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
public interface IUserWorldPrefixService extends IService<UserWorldPrefix> {

    List<UserWorldPrefix> listBaseInfoByUserId(Long userId);

    void createUserWorld(Long userId, UserWorldPrefix userWorldPrefix);

    UserWorldPrefix getUserWorld(Long userId, Long id);

    void updateUserWorld(Long userId, Long id, UserWorldPrefix userWorldPrefix);

    void deleteUserWorld(Long userId, Long id);

    UserWorldPrefix checkUserWorldAuth(Long userWorldId, boolean needUserWorldPrefix);

    UserWorldPrefix checkUserWorldAuth(Long userId, Long userWorldId, boolean needUserWorldPrefix);

    String buildWorldPrompt(Long worldId);
}
