package com.me.galchat.service;

import com.me.galchat.domain.po.UserWorldPrefix;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
public interface IUserWorldPrefixService extends IService<UserWorldPrefix> {

    UserWorldPrefix getByUserIdAndWorldId(Long userId, Long worldId);
}
