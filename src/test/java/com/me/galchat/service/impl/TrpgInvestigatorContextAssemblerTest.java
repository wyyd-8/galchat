package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class TrpgInvestigatorContextAssemblerTest {

    @Test
    void explorationContainsFullOwnedCardIncludingDefaultsWeaponsAndProfile() {
        Fixture fixture = fixture();

        String context = fixture.assembler().format(
                fixture.conversation(),
                action(GroupChatConstant.ACTION_TRPG_SCENE));

        assertThat(context)
                .contains("谨慎、好奇")
                .contains("Agent身份名（非调查员名）：原角色林登")
                .doesNotContain("原角色名：")
                .contains("COC跑团偏好：倾向优先调查无人探索的地点")
                .contains("跑团偏好是行动建议，不是必须遵守的规则")
                .contains("林登")
                .contains("图书馆使用=70")
                .contains("聆听=20")
                .contains("相信知识能够解决问题")
                .contains("我的导师")
                .contains("关键联结：重要之人 / 我的导师")
                .contains("左轮手枪")
                .contains("弹药5/6")
                .contains("状态正常")
                .contains("撬棍和提灯")
                .contains("资产：旧报社股份")
                .contains("消费水平：10")
                .contains("现金：25")
                .doesNotContain("世界背景秘密")
                .contains("调查记录只供本人使用");
    }

    @Test
    void combatAlsoKeepsFullOwnedProfile() {
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
                .contains("相信知识能够解决问题")
                .contains("调查记录只供本人使用")
                .doesNotContain("世界背景秘密");
    }

    @Test
    void resolvesTheBoundCardByPrimaryKey() {
        Fixture fixture = fixture();

        fixture.assembler().format(
                fixture.conversation(),
                action(GroupChatConstant.ACTION_TRPG_SCENE));

        verify(fixture.characterMapper()).selectById(51L);
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
        CocSkillDefMapper skillDefMapper =
                mock(CocSkillDefMapper.class);
        CocCharacter card = new CocCharacter()
                .setId(51L)
                .setRunId(5L)
                .setParticipantId(9L)
                .setActorType("BOT")
                .setName("林登")
                .setOccupation("记者")
                .setStr(45).setCon(55).setSiz(50).setDex(60)
                .setApp(50).setIntValue(70).setPow(55).setEdu(65)
                .setHpCurrent(9).setHpMax(11)
                .setSanCurrent(48).setSanMax(55)
                .setMpCurrent(8).setMpMax(11)
                .setLuckCurrent(40);
        when(characterMapper.selectById(51L)).thenReturn(card);
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
                        .setBaseValue(20)
                        .setValue(70),
                new CocCharacterSkill()
                        .setDisplayName("聆听")
                        .setBaseValue(20)
                        .setValue(20)));
        when(profileMapper.selectList(any())).thenReturn(List.of(
                new CocCharacterProfile()
                        .setIdeology("相信知识能够解决问题")
                        .setSignificantPeople("我的导师")
                        .setKeyConnectionCategory("重要之人")
                        .setKeyConnectionText("我的导师")
                        .setEquipmentText("撬棍和提灯")
                        .setAssetsText("旧报社股份")
                        .setSpendingLevel("10")
                        .setCash("25")
                        .setNotes("调查记录只供本人使用")));
        when(weaponMapper.selectList(any())).thenReturn(List.of(
                new CocCharacterWeapon()
                        .setName("左轮手枪")
                        .setDamage("1d10")
                        .setAmmoCapacity(6)
                        .setRemainingAmmo(5)
                        .setIsBroken(false)));
        CocSkillDef libraryUse = new CocSkillDef();
        libraryUse.setId(1L);
        libraryUse.setName("图书馆使用");
        libraryUse.setBaseValue(20);
        CocSkillDef listen = new CocSkillDef();
        listen.setId(2L);
        listen.setName("聆听");
        listen.setBaseValue(20);
        when(skillDefMapper.selectList(null)).thenReturn(List.of(
                libraryUse, listen));
        return new Fixture(
                new TrpgInvestigatorContextAssembler(
                        characterMapper, templateMapper, skillMapper,
                        profileMapper, weaponMapper,
                        skillDefMapper,
                        new CharacterSkillResolver()),
                characterMapper,
                new GroupConversation()
                        .setId(5L)
                        .setUserWorldId(5L));
    }

    private GroupActionSpec action(String actionType) {
        return new GroupActionSpec(
                actionType,
                GroupChatConstant.ACTOR_CHARACTER,
                9L,
                51L,
                "scene:21",
                "酒店",
                1,
                1);
    }

    private record Fixture(
            TrpgInvestigatorContextAssembler assembler,
            CocCharacterMapper characterMapper,
            GroupConversation conversation) {
    }
}
