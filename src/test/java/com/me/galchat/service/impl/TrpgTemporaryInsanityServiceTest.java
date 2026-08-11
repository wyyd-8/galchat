package com.me.galchat.service.impl;

import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.mapper.CocCharacterMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgTemporaryInsanityServiceTest {

    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusTableInfo() {
        com.me.galchat.support.MybatisPlusTestSupport.initialize(
                CocCharacter.class);
    }

    @Test
    void subtractsThreeHoursAndClearsDurationsAtTheBoundary() {
        CocCharacterMapper mapper = mock(CocCharacterMapper.class);
        TrpgTemporaryInsanityService service =
                new TrpgTemporaryInsanityService(mapper);
        CocCharacter continuing = temporaryInsanity(1L, 4);
        CocCharacter expiring = temporaryInsanity(2L, 3);
        when(mapper.selectList(any())).thenReturn(
                List.of(continuing, expiring));
        when(mapper.updateById(any(CocCharacter.class))).thenReturn(1);
        when(mapper.update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);

        service.advanceAfterLargeScene(7L);

        assertThat(continuing.getTemporaryInsanity()).isTrue();
        assertThat(continuing.getTemporaryInsanityPhase())
                .isEqualTo("REAL_TIME");
        assertThat(continuing.getTemporaryInsanityRemainingHours())
                .isEqualTo(1);
        assertThat(expiring.getTemporaryInsanity()).isFalse();
        assertThat(expiring.getTemporaryInsanityPhase()).isNull();
        assertThat(expiring.getTemporaryInsanityRemainingHours()).isNull();
        verify(mapper).updateById(continuing);
        verify(mapper).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    private CocCharacter temporaryInsanity(Long id, int hours) {
        return new CocCharacter()
                .setId(id)
                .setRunId(7L)
                .setTemporaryInsanity(true)
                .setTemporaryInsanityPhase("REAL_TIME")
                .setTemporaryInsanityRemainingHours(hours);
    }
}
