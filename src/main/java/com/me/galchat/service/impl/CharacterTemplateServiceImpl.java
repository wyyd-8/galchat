package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.service.ICharacterTemplateService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-08
 */
@Service
public class CharacterTemplateServiceImpl extends ServiceImpl<CharacterTemplateMapper, CharacterTemplate> implements ICharacterTemplateService {

    @Override
    public List<CharacterTemplate> listCharacterBaseInfoByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return lambdaQuery()
                .select(CharacterTemplate::getId, CharacterTemplate::getName, CharacterTemplate::getImage)
                .in(CharacterTemplate::getId, ids)
                .list();
    }

    @Override
    public CharacterTemplate getCharacterTemplateById(Long id) {
        CharacterTemplate template = getById(id);
        if (template == null) {
            throw new UserRequestException("角色模板不存在");
        }
        return template;
    }
}
