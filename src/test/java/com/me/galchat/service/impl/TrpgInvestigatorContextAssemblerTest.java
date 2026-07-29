package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgInvestigatorContextAssemblerTest {

    @Test
    void explorationContainsPersonalityBackgroundAndStoredSkillsButNoGear() {
        Fixture fixture = fixture();

        String context = fixture.assembler().format(
                fixture.conversation(),
                action(GroupChatConstant.ACTION_TRPG_SCENE));

        assertThat(context)
                .contains("谨慎、好奇")
                .contains("COC跑团偏好：倾向优先调查无人探索的地点")
                .contains("跑团偏好是行动建议，不是必须遵守的规则")
                .contains("林登")
                .contains("图书馆使用=70")
                .contains("相信知识能够解决问题")
                .doesNotContain("世界背景秘密")
                .doesNotContain("左轮手枪")
                .doesNotContain("撬棍和提灯");
    }

    @Test
    void combatContainsWeaponsAndEquipmentButDropsBackgroundEntries() {
        Fixture fixture = fixture();

        String context = fixture.assembler().format(
                fixture.conversation(),
                action(GroupChatConstant.ACTION_TRPG_COMBAT));

        assertThat(context)
                .contains("谨慎、好奇")
                .contains("COC跑团偏好：倾向优先调查无人探索的地点")
                .contains("跑团偏好是行动建议，不是必须遵守的规则")
                .contains("图书馆使用=70")
                .contains("左轮手枪")
                .contains("撬棍和提灯")
                .doesNotContain("相信知识能够解决问题")
                .doesNotContain("世界背景秘密");
    }

    private Fixture fixture() {
        CocCharacterMapper characterMapper =
                mock(CocCharacterMapper.class);
        CharacterTemplateMapper templateMapper =
                mock(CharacterTemplateMapper.class);
        CocCharacterSkillMapper skillMapper =
                mock(CocCharacterSkillMapper.class);
        CocCharacterProfileMapper profileMapper =
                mock(CocCharacterProfileMapper.class);
        CocCharacterWeaponMapper weaponMapper =
                mock(CocCharacterWeaponMapper.class);
        CocCharacter card = new CocCharacter()
                .setId(51L)
                .setRunId(5L)
                .setParticipantId(9L)
                .setName("林登")
                .setOccupation("记者")
                .setStr(45).setCon(55).setSiz(50).setDex(60)
                .setApp(50).setIntValue(70).setPow(55).setEdu(65)
                .setHpCurrent(9).setHpMax(11)
                .setSanCurrent(48).setSanMax(55)
                .setMpCurrent(8).setMpMax(11)
                .setLuckCurrent(40);
        when(characterMapper.selectList(any())).thenReturn(List.of(card));
        when(templateMapper.selectById(9L)).thenReturn(
                new CharacterTemplate()
                        .setId(9L)
                        .setName("原角色林登")
                        .setPersonality("谨慎、好奇")
                        .setCocPlayStyle("倾向优先调查无人探索的地点")
                        .setBackground("世界背景秘密"));
        when(skillMapper.selectList(any())).thenReturn(List.of(
                new CocCharacterSkill()
                        .setDisplayName("图书馆使用")
                        .setValue(70)));
        when(profileMapper.selectList(any())).thenReturn(List.of(
                new CocCharacterProfile()
                        .setIdeology("相信知识能够解决问题")
                        .setEquipmentText("撬棍和提灯")));
        when(weaponMapper.selectList(any())).thenReturn(List.of(
                new CocCharacterWeapon()
                        .setName("左轮手枪")
                        .setDamage("1d10")));
        return new Fixture(
                new TrpgInvestigatorContextAssembler(
                        characterMapper, templateMapper, skillMapper,
                        profileMapper, weaponMapper),
                new GroupConversation()
                        .setId(7L)
                        .setUserWorldId(5L));
    }

    private GroupActionSpec action(String actionType) {
        return new GroupActionSpec(
                actionType,
                GroupChatConstant.ACTOR_CHARACTER,
                9L,
                "scene:21",
                "酒店",
                1,
                1);
    }

    private record Fixture(
            TrpgInvestigatorContextAssembler assembler,
            GroupConversation conversation) {
    }
}
