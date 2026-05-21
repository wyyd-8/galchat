package com.me.galchat.service;

import com.me.galchat.domain.po.UserEventLog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
public interface IUserEventLogService extends IService<UserEventLog> {

    boolean addUserEventLog(UserEventLog userEventLog);

    List<UserEventLog> listUserEventLogs(Long userWorldId, LocalDateTime beginTime, LocalDateTime endTime);

}
