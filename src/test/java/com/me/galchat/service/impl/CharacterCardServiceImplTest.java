package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

class CharacterCardServiceImplTest {

    private CocCharacterMapper characterMapper;
    private CocSkillDefMapper skillDefMapper;
    private CharacterCardServiceImpl service;

    @BeforeEach
    void setUp() {
        characterMapper = mock(CocCharacterMapper.class);
        skillDefMapper = mock(CocSkillDefMapper.class);
        service = new CharacterCardServiceImpl(characterMapper, mock(CocCharacterSkillMapper.class),
                mock(CocCharacterWeaponMapper.class), mock(CocCharacterProfileMapper.class), skillDefMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollsAndBindsLuckOnlyOnce() {
        when(characterMapper.selectById(1L)).thenReturn(new CocCharacter().setId(1L));
        when(characterMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        int result = service.rollLuck(1L).getResult();

        assertThat(result).isBetween(15, 90);
        assertThat(result % 5).isZero();
    }

    @Test
    void rejectsRerollWhenLuckIsAlreadyBound() {
        when(characterMapper.selectById(1L)).thenReturn(new CocCharacter().setId(1L).setLuckCurrent(65));

        assertThatThrownBy(() -> service.rollLuck(1L))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("幸运值已绑定");
    }

    @Test
    void rejectsNonZeroCthulhuMythos() {
        when(skillDefMapper.selectList(null)).thenReturn(List.of());
        CocCharacter character = characterWithAllAttributes(50);
        CocCharacterSkill mythos = new CocCharacterSkill().setDisplayName("克苏鲁神话").setValue(1);

        assertThatThrownBy(() -> service.validateAndFillSkills(character, List.of(mythos)))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("新建角色卡的克苏鲁神话点数必须为0");
    }

    @Test
    void rejectsSkillPointsOverCalculatedBudget() {
        when(skillDefMapper.selectList(null)).thenReturn(List.of());
        CocCharacter character = characterWithAllAttributes(50);
        CocCharacterSkill custom = new CocCharacterSkill().setDisplayName("自定义技能").setValue(301);

        assertThatThrownBy(() -> service.validateAndFillSkills(character, List.of(custom)))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("当前消耗301，上限300");
    }

    @Test
    void resolvesFormulaBaseValueAndActorType() {
        CocSkillDef dodge = new CocSkillDef();
        dodge.setId(1L);
        dodge.setName("闪避");
        dodge.setCategory("战斗");
        dodge.setBaseFormula("DEX/2");
        when(skillDefMapper.selectList(null)).thenReturn(List.of(dodge));
        CocCharacter character = characterWithAllAttributes(50).setDex(41);
        CocCharacterSkill skill = new CocCharacterSkill().setDisplayName("闪避").setValue(20);

        service.validateAndFillSkills(character, List.of(skill));

        assertThat(skill.getBaseValue()).isEqualTo(20);
        assertThat(skill.getSkillDefId()).isEqualTo(1L);
        assertThat(CharacterCardServiceImpl.resolveActorType(null)).isEqualTo("PLAYER");
        assertThat(CharacterCardServiceImpl.resolveActorType(9L)).isEqualTo("BOT");
    }

    private CocCharacter characterWithAllAttributes(int value) {
        return new CocCharacter().setStr(value).setCon(value).setSiz(value).setDex(value)
                .setApp(value).setIntValue(value).setPow(value).setEdu(value);
    }
}
