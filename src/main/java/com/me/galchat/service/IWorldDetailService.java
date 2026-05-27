package com.me.galchat.service;

import com.me.galchat.domain.po.WorldDetail;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
public interface IWorldDetailService extends IService<WorldDetail> {

    WorldDetail createWorldDetail(Long userId, Long worldId, WorldDetail worldDetail);

    List<WorldDetail> listWorldDetails(Long userId, Long worldId);
}
