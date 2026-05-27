package com.me.galchat.service;

import com.me.galchat.domain.po.CharacterTemplate;
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
public interface ICharacterTemplateService extends IService<CharacterTemplate> {

    List<CharacterTemplate> listCharacterBaseInfoByIds(List<Long> ids);

    CharacterTemplate getCharacterTemplateById(Long id);

    CharacterTemplate createCharacterTemplate(Long userId, Long worldId, CharacterTemplate characterTemplate);
}
