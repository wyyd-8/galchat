package com.me.galchat.service.impl.trpg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.mapper.CocCharacterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TrpgTemporaryInsanityService {

    private static final int LARGE_SCENE_HOURS = 3;

    private final CocCharacterMapper characterMapper;

    @Transactional(rollbackFor = Exception.class)
    public void advanceAfterLargeScene(Long runId) {
        if (runId == null) {
            throw new IllegalArgumentException("runId不能为空");
        }
        List<CocCharacter> characters = characterMapper.selectList(
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId, runId)
                        .eq(CocCharacter::getTemporaryInsanity, true)
                        .isNotNull(CocCharacter::getTemporaryInsanityRemainingHours)
                        .orderByAsc(CocCharacter::getId));
        if (characters == null) {
            return;
        }
        for (CocCharacter character : characters) {
            int remaining = character.getTemporaryInsanityRemainingHours();
            if (remaining <= LARGE_SCENE_HOURS) {
                character.setTemporaryInsanity(false)
                        .setTemporaryInsanityPhase(null)
                        .setTemporaryInsanityRemainingHours(null);
            } else {
                character.setTemporaryInsanityRemainingHours(
                        remaining - LARGE_SCENE_HOURS);
            }
            character.setUpdatedAt(LocalDateTime.now());
            int updated;
            if (remaining <= LARGE_SCENE_HOURS) {
                updated = characterMapper.update(null,
                        new LambdaUpdateWrapper<CocCharacter>()
                                .eq(CocCharacter::getId,
                                        character.getId())
                                .set(CocCharacter::getTemporaryInsanity,
                                        false)
                                .set(CocCharacter
                                                ::getTemporaryInsanityPhase,
                                        null)
                                .set(CocCharacter
                                                ::getTemporaryInsanityRemainingHours,
                                        null)
                                .set(CocCharacter::getUpdatedAt,
                                        character.getUpdatedAt()));
            } else {
                updated = characterMapper.updateById(character);
            }
            if (updated == 0) {
                throw new IllegalStateException("临时疯狂状态更新时间失败");
            }
        }
    }
}
