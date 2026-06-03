package com.me.galchat.service.impl;

import com.me.galchat.domain.po.WorldDetail;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.WorldDetailMapper;
import com.me.galchat.service.IWorldDetailService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.vector.WorldDetailVectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
@Service
@RequiredArgsConstructor
public class WorldDetailServiceImpl extends ServiceImpl<WorldDetailMapper, WorldDetail> implements IWorldDetailService {

    private final IWorldTemplateService worldTemplateService;
    private final WorldDetailVectorService worldDetailVectorService;

    @Override
    @Transactional
    public void createWorldDetail(Long userId, Long worldId, WorldDetail worldDetail) {
        if (worldDetail == null) {
            throw new UserRequestException("请求参数不能为空");
        }
        if (worldDetail.getDetails().length() > 2000) {
            throw new UserRequestException("世界详情内容过长，不能超过2000字");
        }
        checkWorldAuthor(userId, worldId);

        WorldDetail newWorldDetail = new WorldDetail()
                .setWorldId(worldId)
                .setAbout(worldDetail.getAbout())
                .setDetails(worldDetail.getAbout() + ":\n" + worldDetail.getDetails());
        save(newWorldDetail);
        worldDetailVectorService.addWorldDetail(newWorldDetail);
    }

    @Override
    public List<WorldDetail> listWorldDetails(Long userId, Long worldId) {
        checkWorldAuthor(userId, worldId);
        return lambdaQuery()
                .eq(WorldDetail::getWorldId, worldId)
                .list();
    }

    @Override
    public void deleteWorldDetail(Long userId, Long worldId, Long detailId) {
        checkWorldAuthor(userId, worldId);
        lambdaUpdate().eq(WorldDetail::getId, detailId)
                .eq(WorldDetail::getWorldId, worldId)
                .remove();
    }

    private void checkWorldAuthor(Long userId, Long worldId) {
        if (worldId == null) {
            throw new UserRequestException("世界模板id不能为空");
        }
        WorldTemplate worldTemplate = worldTemplateService.getById(worldId);
        if (worldTemplate == null) {
            throw new UserRequestException("世界模板不存在");
        }
        if (!Objects.equals(worldTemplate.getAuthorId(), userId)) {
            throw new UserAuthException("无权访问该世界详情");
        }
    }
}
