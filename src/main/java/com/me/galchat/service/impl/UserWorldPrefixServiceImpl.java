package com.me.galchat.service.impl;

import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.mapper.UserWorldPrefixMapper;
import com.me.galchat.service.IUserWorldPrefixService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
@Service
public class UserWorldPrefixServiceImpl extends ServiceImpl<UserWorldPrefixMapper, UserWorldPrefix> implements IUserWorldPrefixService {

    @Override
    public UserWorldPrefix getByUserIdAndWorldId(Long userId, Long worldId) {
        return lambdaQuery()
                .eq(UserWorldPrefix::getUserId, userId)
                .eq(UserWorldPrefix::getWorldId, worldId)
                .one();
    }
}
