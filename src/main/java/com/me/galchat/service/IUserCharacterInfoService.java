package com.me.galchat.service;

import com.me.galchat.domain.po.UserCharacterInfo;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author author
 * @since 2026-05-08
 */
public interface IUserCharacterInfoService extends IService<UserCharacterInfo> {

    UserCharacterInfo addCharacter(Long userWorldId, Long characterId);

    void deleteCharacter(Long userWorldId, Long characterId);

    List<UserCharacterInfo> listByUserWorldId(Long userWorldId);

    Integer updateFavorValue(Long userWorldId, Long characterId, Integer favorChange);

    String buildCharacterPrompt(Long userWorldId, Long characterId);
}
