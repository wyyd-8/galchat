package com.me.galchat.service.impl.user;

import com.me.galchat.domain.po.UserEventLog;
import com.me.galchat.mapper.UserEventLogMapper;
import com.me.galchat.service.IUserEventLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
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
public class UserEventLogServiceImpl extends ServiceImpl<UserEventLogMapper, UserEventLog> implements IUserEventLogService {
    @Override
    public boolean addUserEventLog(UserEventLog userEventLog) {
        if (userEventLog == null) {
            return false;
        }
        save(userEventLog);
        return true;
    }

    @Override
    public List<UserEventLog> listUserEventLogs(Long userWorldId, LocalDateTime beginTime, LocalDateTime endTime) {
        if (beginTime == null || endTime == null) {
            return List.of();
        }
        if (beginTime.isAfter(endTime)) {
            return List.of();
        }

        return lambdaQuery()
                .eq(UserEventLog::getUserWorldId, userWorldId)
                .ge(UserEventLog::getTime, beginTime)
                .le(UserEventLog::getTime, endTime)
                .orderByAsc(UserEventLog::getTime)
                .orderByAsc(UserEventLog::getId)
                .list();
    }

    @Override
    public List<UserEventLog> listUpcomingUserEventLogs(LocalDateTime beginTime, LocalDateTime endTime) {
        if (beginTime == null || endTime == null || !beginTime.isBefore(endTime)) {
            return List.of();
        }

        return lambdaQuery()
                .isNotNull(UserEventLog::getUserWorldId)
                .isNotNull(UserEventLog::getCharacterId)
                .isNotNull(UserEventLog::getTime)
                .ge(UserEventLog::getTime, beginTime)
                .lt(UserEventLog::getTime, endTime)
                .orderByAsc(UserEventLog::getTime)
                .orderByAsc(UserEventLog::getUserWorldId)
                .orderByAsc(UserEventLog::getCharacterId)
                .orderByAsc(UserEventLog::getId)
                .list();
    }

}
