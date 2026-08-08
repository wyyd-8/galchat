package com.me.galchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.CocSkillDefMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.StringJoiner;

@Component
@RequiredArgsConstructor
public class TrpgInvestigatorContextAssembler {

    private final CocCharacterMapper characterMapper;
    private final CharacterTemplateMapper templateMapper;
    private final CocCharacterSkillMapper skillMapper;
    private final CocCharacterProfileMapper profileMapper;
    private final CocCharacterWeaponMapper weaponMapper;
    private final CocSkillDefMapper skillDefMapper;
    private final CharacterSkillResolver skillResolver;

    public String format(
            GroupConversation conversation, GroupActionSpec action) {
        CocCharacter card = requireCard(conversation, action);
        StringBuilder result = new StringBuilder(
                "<controlled-investigator>");
        append(result, "调查员", card.getName());
        if (GroupChatConstant.ACTOR_CHARACTER.equals(action.actorType())) {
            CharacterTemplate template =
                    templateMapper.selectById(action.actorId());
            if (template != null) {
                append(result, "原角色名", template.getName());
                append(result, "角色性格", template.getPersonality());
                if (StringUtils.hasText(template.getCocPlayStyle())) {
                    append(result, "COC跑团偏好",
                            template.getCocPlayStyle());
                    result.append("\n跑团偏好是行动建议，不是必须遵守的规则；")
                            .append("请结合当前情境和角色性格决定行动。");
                }
            }
        }
        append(result, "职业", card.getOccupation());
        append(result, "性别", card.getSex());
        append(result, "年龄", card.getAge());
        append(result, "时代", card.getEra());
        append(result, "出生地", card.getBirthplace());
        append(result, "居住地", card.getResidence());
        appendAttributes(result, card);
        appendStatuses(result, card);
        appendSkills(result, card);
        appendWeapons(result, card.getId());
        CocCharacterProfile profile = first(profileMapper.selectList(
                new LambdaQueryWrapper<CocCharacterProfile>()
                        .eq(CocCharacterProfile::getCharacterId,
                                card.getId())
                        .last("limit 1")));
        if (profile != null) {
            appendBackground(result, profile);
        }
        return result.append("\n</controlled-investigator>")
                .toString();
    }

    private CocCharacter requireCard(
            GroupConversation conversation, GroupActionSpec action) {
        LambdaQueryWrapper<CocCharacter> query =
                new LambdaQueryWrapper<CocCharacter>()
                        .eq(CocCharacter::getRunId,
                                conversation.getId());
        if (GroupChatConstant.ACTOR_USER.equals(action.actorType())) {
            query.eq(CocCharacter::getId, action.actorId())
                    .eq(CocCharacter::getActorType, "PLAYER");
        } else {
            query.eq(CocCharacter::getParticipantId,
                    action.actorId());
        }
        List<CocCharacter> cards = characterMapper.selectList(query);
        if (cards == null || cards.size() != 1) {
            throw new UserRequestException(
                    "无法唯一确定当前行动调查员的人物卡");
        }
        return cards.getFirst();
    }

    private void appendAttributes(
            StringBuilder result, CocCharacter card) {
        result.append("\n属性：STR=").append(value(card.getStr()))
                .append("，CON=").append(value(card.getCon()))
                .append("，SIZ=").append(value(card.getSiz()))
                .append("，DEX=").append(value(card.getDex()))
                .append("，APP=").append(value(card.getApp()))
                .append("，INT=").append(value(card.getIntValue()))
                .append("，POW=").append(value(card.getPow()))
                .append("，EDU=").append(value(card.getEdu()));
        result.append("\n资源：HP=")
                .append(pair(card.getHpCurrent(), card.getHpMax()))
                .append("，SAN=")
                .append(pair(card.getSanCurrent(), card.getSanMax()))
                .append("，MP=")
                .append(pair(card.getMpCurrent(), card.getMpMax()))
                .append("，幸运=").append(value(card.getLuckCurrent()))
                .append("，护甲=").append(value(card.getArmor()))
                .append("，DB=").append(value(card.getDamageBonus()))
                .append("，体格=").append(value(card.getBuild()))
                .append("，MOV=").append(value(card.getMov()));
    }

    private void appendStatuses(
            StringBuilder result, CocCharacter card) {
        StringJoiner statuses = new StringJoiner("、");
        addStatus(statuses, card.getMajorWound(), "重伤");
        addStatus(statuses, card.getUnconscious(), "昏迷");
        addStatus(statuses, card.getDying(), "濒死");
        addStatus(statuses, card.getDead(), "死亡");
        addStatus(statuses, card.getTemporaryInsanity(), "临时疯狂");
        if (statuses.length() > 0) {
            append(result, "状态", statuses.toString());
        }
    }

    private void appendSkills(StringBuilder result, CocCharacter card) {
        List<CocCharacterSkill> skills = skillResolver.resolveEffectiveSkills(
                card,
                skillMapper.selectList(
                        new LambdaQueryWrapper<CocCharacterSkill>()
                                .eq(CocCharacterSkill::getCharacterId, card.getId())
                                .orderByAsc(CocCharacterSkill::getId)),
                skillDefMapper.selectList(null));
        if (skills == null || skills.isEmpty()) {
            return;
        }
        StringJoiner values = new StringJoiner("，");
        skills.forEach(skill -> values.add(
                skill.getDisplayName() + "=" + value(skill.getValue())));
        append(result, "技能", values.toString());
    }

    private void appendWeapons(StringBuilder result, Long cardId) {
        List<CocCharacterWeapon> weapons = weaponMapper.selectList(
                new LambdaQueryWrapper<CocCharacterWeapon>()
                        .eq(CocCharacterWeapon::getCharacterId, cardId)
                        .orderByAsc(CocCharacterWeapon::getId));
        if (weapons == null || weapons.isEmpty()) {
            return;
        }
        StringJoiner values = new StringJoiner("；");
        weapons.forEach(weapon -> values.add(
                weapon.getName() + "（伤害"
                        + value(weapon.getDamage()) + "，剩余弹药"
                        + value(weapon.getRemainingAmmo()) + "）"));
        append(result, "武器", values.toString());
    }

    private void appendBackground(
            StringBuilder result, CocCharacterProfile profile) {
        append(result, "外貌", profile.getAppearance());
        append(result, "思想与信念", profile.getIdeology());
        append(result, "重要之人", profile.getSignificantPeople());
        append(result, "意义非凡之地", profile.getMeaningfulLocations());
        append(result, "宝贵之物", profile.getTreasuredPossessions());
        append(result, "特质", profile.getTraits());
        if (StringUtils.hasText(profile.getKeyConnectionCategory())
                || StringUtils.hasText(profile.getKeyConnectionText())) {
            append(result, "关键联结",
                    value(profile.getKeyConnectionCategory()) + " / "
                            + value(profile.getKeyConnectionText()));
        }
        append(result, "伤口与疤痕", profile.getInjuriesAndScars());
        append(result, "恐惧与狂躁", profile.getPhobiasAndManias());
        append(result, "携带装备", profile.getEquipmentText());
        append(result, "资产", profile.getAssetsText());
        append(result, "消费水平", profile.getSpendingLevel());
        append(result, "现金", profile.getCash());
        append(result, "备注", profile.getNotes());
    }

    private void addStatus(
            StringJoiner statuses, Boolean enabled, String name) {
        if (Boolean.TRUE.equals(enabled)) {
            statuses.add(name);
        }
    }

    private void append(
            StringBuilder result, String label, Object content) {
        if (content != null && StringUtils.hasText(content.toString())) {
            result.append('\n').append(label).append('：')
                    .append(content);
        }
    }

    private String pair(Integer current, Integer maximum) {
        return value(current) + "/" + value(maximum);
    }

    private String value(Object value) {
        return value == null ? "未知" : value.toString();
    }

    private <T> T first(List<T> values) {
        return values == null || values.isEmpty()
                ? null : values.getFirst();
    }
}
