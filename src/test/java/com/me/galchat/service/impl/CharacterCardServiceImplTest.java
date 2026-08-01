package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocSkillDef;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.dto.CharacterCardCreateDTO;
import com.me.galchat.domain.dto.KpCharacterAttributeDTOs;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.UserInfo;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.mapper.UserInfoMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.List;

class CharacterCardServiceImplTest {

    private CocCharacterMapper characterMapper;
    private CocCharacterSkillMapper skillMapper;
    private CocSkillDefMapper skillDefMapper;
    private CharacterTemplateMapper characterTemplateMapper;
    private UserInfoMapper userInfoMapper;
    private GroupConversationMapper conversationMapper;
    private CharacterCardServiceImpl service;

    @BeforeEach
    void setUp() {
        characterMapper = mock(CocCharacterMapper.class);
        skillMapper = mock(CocCharacterSkillMapper.class);
        skillDefMapper = mock(CocSkillDefMapper.class);
        characterTemplateMapper = mock(CharacterTemplateMapper.class);
        userInfoMapper = mock(UserInfoMapper.class);
        conversationMapper = mock(GroupConversationMapper.class);
        service = new CharacterCardServiceImpl(characterMapper, skillMapper,
                mock(CocCharacterWeaponMapper.class), mock(CocCharacterProfileMapper.class), skillDefMapper,
                characterTemplateMapper, userInfoMapper,
                conversationMapper);
    }

    @AfterEach
    void tearDown() {
        CurrentHolder.remove();
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

    @Test
    void fillsBotPlayerNameAndImageFromCharacterTemplate() {
        when(characterTemplateMapper.selectById(9L)).thenReturn(new CharacterTemplate()
                .setName("守秘人").setImage("https://example.com/npc.png"));
        CocCharacter character = new CocCharacter();

        service.fillPlayerAndImage(character, 9L);

        assertThat(character.getPlayerName()).isEqualTo("守秘人");
        assertThat(character.getImage()).isEqualTo("https://example.com/npc.png");
    }

    @Test
    void fillsPlayerNameFromCurrentUser() {
        CurrentHolder.setCurrentId(3);
        when(userInfoMapper.selectById(3L)).thenReturn(new UserInfo().setUsername("player"));
        CocCharacter character = new CocCharacter();

        service.fillPlayerAndImage(character, null);

        assertThat(character.getPlayerName()).isEqualTo("player");
        assertThat(character.getImage()).isNull();
    }

    @Test
    void rejectsPlayerCreationWithoutCurrentUser() {
        assertThatThrownBy(() -> service.fillPlayerAndImage(new CocCharacter(), null))
                .isInstanceOf(UserAuthException.class)
                .hasMessage("用户未登录");
    }

    @Test
    void rejectsCharacterCreationWhenRunIdIsNotATrpgConversation() {
        CharacterCardCreateDTO request = new CharacterCardCreateDTO();
        request.setRunId(5L);
        request.setCharacterText("不应在校验跑团前解析");
        when(conversationMapper.selectById(5L)).thenReturn(
                new GroupConversation()
                        .setId(5L)
                        .setMode(GroupChatConstant.MODE_CHAT));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("runId必须是TRPG群聊id");
    }

    @Test
    void rejectsImportedInvestigatorWhoseNameMatchesAnyRunCharacter() {
        CharacterCardCreateDTO request = new CharacterCardCreateDTO();
        request.setRunId(5L);
        request.setParticipantId(9L);
        request.setCharacterText("""
                食尸鬼，怪物，未知，30岁
                出身墓园，现居地下
                时代: 现代
                STR 50 CON 50 SIZ 50 DEX 50
                APP 50 INT 50 POW 50 EDU 50
                """);
        when(conversationMapper.selectById(5L)).thenReturn(
                new GroupConversation()
                        .setId(5L)
                        .setMode(GroupChatConstant.MODE_TRPG));
        when(characterMapper.selectList(any())).thenReturn(List.of(
                new CocCharacter()
                        .setId(81L)
                        .setRunId(5L)
                        .setActorType("NPC")
                        .setName("食尸鬼")));
        when(skillDefMapper.selectList(null)).thenReturn(List.of());
        when(characterTemplateMapper.selectById(9L)).thenReturn(
                new CharacterTemplate().setName("Agent"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("同一跑团内人物卡名称不能重复：食尸鬼");
    }

    @Test
    void resolvesAttributeAndSkillChecksByRunAndUniqueCardName() {
        CocCharacter card = new CocCharacter()
                .setId(71L).setRunId(5L).setParticipantId(null)
                .setName("林恩").setCon(55).setSanCurrent(63);
        when(characterMapper.selectList(any())).thenReturn(List.of(card));
        when(skillMapper.selectList(any())).thenReturn(List.of(
                new CocCharacterSkill().setCharacterId(71L).setDisplayName("侦查").setValue(70)));

        CocDiceCharacterVO resolved = service.requireDiceCharacter(5L, " 林恩 ");

        assertThat(resolved.checkValues())
                .containsEntry("CON", 55)
                .containsEntry("con", 55)
                .containsEntry("体质", 55)
                .containsEntry("SAN", 63)
                .containsEntry("理智", 63)
                .containsEntry("侦查", 70);
    }

    @Test
    void rejectsMissingCardNamesInsideOneRun() {
        when(characterMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.requireDiceCharacter(5L, "林恩"))
                .hasMessage("人物卡不存在");
    }

    @Test
    void rejectsAmbiguousCardNamesInsideOneRun() {
        when(characterMapper.selectList(any())).thenReturn(List.of(
                new CocCharacter().setId(71L).setRunId(5L).setName("林恩"),
                new CocCharacter().setId(72L).setRunId(5L).setName("林恩")));

        assertThatThrownBy(() -> service.requireDiceCharacter(5L, "林恩"))
                .hasMessage("人物卡名称不唯一");
    }

    @Test
    void updatesQuickNotesByExactNameInsideRun() {
        CocCharacter card = new CocCharacter()
                .setId(71L).setRunId(5L).setName("林恩");
        when(characterMapper.selectList(any())).thenReturn(List.of(card));
        when(characterMapper.updateById(card)).thenReturn(1);

        service.updateQuickNotes(5L, " 林恩 ", " 已感染第一阶段 ");

        assertThat(card.getQuickNotes()).isEqualTo("已感染第一阶段");
        verify(characterMapper).updateById(card);
    }

    @Test
    void adjustsOnlyRequestedAttributesAndRecalculatesDamageBonusAndBuild() {
        CocCharacter card = characterWithAllAttributes(50)
                .setId(71L).setRunId(5L).setName("林恩")
                .setStr(60).setSiz(60)
                .setDamageBonus("0").setBuild(0).setMov(8)
                .setHpCurrent(11).setHpMax(11)
                .setSanCurrent(42).setSanMax(99)
                .setMpCurrent(10).setMpMax(10)
                .setLuckCurrent(55).setQuickNotes("保持不变");
        when(characterMapper.selectList(any())).thenReturn(List.of(card));
        when(characterMapper.updateById(card)).thenReturn(1);
        KpCharacterAttributeDTOs.Adjustments adjustments =
                new KpCharacterAttributeDTOs.Adjustments(
                        10, null, null, null, -65, null, null, 60);

        KpCharacterAttributeDTOs.Result result =
                service.adjustBasicAttributes(
                        5L, " 林恩 ", adjustments);

        assertThat(card.getStr()).isEqualTo(70);
        assertThat(card.getApp()).isZero();
        assertThat(card.getEdu()).isEqualTo(100);
        assertThat(card.getCon()).isEqualTo(50);
        assertThat(card.getDamageBonus()).isEqualTo("+1D4");
        assertThat(card.getBuild()).isEqualTo(1);
        assertThat(card)
                .extracting(
                        CocCharacter::getMov,
                        CocCharacter::getHpCurrent,
                        CocCharacter::getHpMax,
                        CocCharacter::getSanCurrent,
                        CocCharacter::getSanMax,
                        CocCharacter::getMpCurrent,
                        CocCharacter::getMpMax,
                        CocCharacter::getLuckCurrent,
                        CocCharacter::getQuickNotes)
                .containsExactly(
                        8, 11, 11, 42, 99, 10, 10, 55,
                        "保持不变");
        assertThat(result.characterName()).isEqualTo("林恩");
        assertThat(result.changes())
                .containsEntry("STR",
                        new KpCharacterAttributeDTOs.ValueChange(60, 70))
                .containsEntry("APP",
                        new KpCharacterAttributeDTOs.ValueChange(50, 0))
                .containsEntry("EDU",
                        new KpCharacterAttributeDTOs.ValueChange(50, 100))
                .doesNotContainKey("CON");
        assertThat(result.damageBonus()).isEqualTo("+1D4");
        assertThat(result.build()).isEqualTo(1);
        verify(characterMapper).updateById(card);
    }

    @Test
    void rejectsEmptyBasicAttributeAdjustmentWithoutUpdatingCard() {
        assertThatThrownBy(() -> service.adjustBasicAttributes(
                5L, "林恩",
                new KpCharacterAttributeDTOs.Adjustments(
                        null, null, null, null,
                        null, null, null, null)))
                .isInstanceOf(UserRequestException.class)
                .hasMessage("至少需要提供一个基础属性修正值");
        org.mockito.Mockito.verify(characterMapper,
                org.mockito.Mockito.never())
                .updateById(any(CocCharacter.class));
    }

    private CocCharacter characterWithAllAttributes(int value) {
        return new CocCharacter().setStr(value).setCon(value).setSiz(value).setDex(value)
                .setApp(value).setIntValue(value).setPow(value).setEdu(value);
    }
}
