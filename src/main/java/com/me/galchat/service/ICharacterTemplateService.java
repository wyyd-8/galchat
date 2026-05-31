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

    CharacterTemplate getCharacterTemplateById(Long id);

    CharacterTemplate getCharacterTemplateByWorldId(Long worldId, Long id);

    List<CharacterTemplate> listCharacterBaseInfoByWorldId(Long userId, Long worldId);

    void createCharacterTemplate(Long userId, Long worldId, CharacterTemplate characterTemplate);

    void updateCharacterTemplate(Long userId, Long worldId, Long id, CharacterTemplate characterTemplate);
}
