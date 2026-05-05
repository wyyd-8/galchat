package com.me.galchat.service.impl;

import com.me.galchat.domain.po.UserInfo;
import com.me.galchat.mapper.UserInfoMapper;
import com.me.galchat.service.IUserInfoService;
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
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

    @Override
    public UserInfo getInfoById(Integer id) {
        return lambdaQuery()
                .select(UserInfo::getId, UserInfo::getUsername)
                .eq(UserInfo::getId, id)
                .one();

    }
}
