package com.me.galchat.service.impl;

import com.me.galchat.constant.InsanityCatalog;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.domain.vo.CharacterCardVO;
import com.me.galchat.domain.vo.CocDiceCharacterVO;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Component
public class CharacterCardContextFormatter {

    public String format(List<CocDiceCharacterVO> cards) {
        return format(cards, "investigator-cards", "investigator-card", true);
    }

    public String formatNpcs(List<CocDiceCharacterVO> cards) {
        if (cards == null || cards.isEmpty()) {
            return "<npc-roster />";
        }
        StringBuilder result = new StringBuilder("<npc-roster>");
        for (CocDiceCharacterVO card : cards) {
            result.append("\n- ").append(escape(card.name()));
        }
        result.append("\n</npc-roster>");
        List<CocDiceCharacterVO> changed = cards.stream()
                .filter(this::hasChangedRuntimeState)
                .toList();
        if (changed.isEmpty()) {
            return result.toString();
        }
        result.append("\n<npc-state-changes>");
        for (CocDiceCharacterVO card : changed) {
            result.append("\n- ").append(escape(card.name()))
                    .append("：HP ").append(value(card.hpCurrent()))
                    .append('/').append(value(card.hpMax()));
            appendInlineStatuses(result, card);
        }
        return result.append("\n</npc-state-changes>").toString();
    }

    public String formatOtherInvestigators(List<CharacterCardVO> cards) {
        if (cards == null || cards.isEmpty()) {
            return "<other-investigators />";
        }
        StringBuilder result = new StringBuilder("<other-investigators>");
        for (CharacterCardVO card : cards) {
            if (card == null || card.getCharacter() == null) {
                continue;
            }
            appendOtherInvestigator(result, card);
        }
        if (result.length() == "<other-investigators>".length()) {
            return "<other-investigators />";
        }
        return result.append("\n</other-investigators>").toString();
    }

    public String formatInvestigatorWeaponStates(
            List<CharacterCardVO> cards) {
        if (cards == null || cards.isEmpty()) {
            return "<investigator-weapon-states />";
        }
        StringBuilder result = new StringBuilder(
                "<investigator-weapon-states>");
        for (CharacterCardVO card : cards) {
            if (card == null || card.getCharacter() == null
                    || card.getWeapons() == null
                    || card.getWeapons().isEmpty()) {
                continue;
            }
            result.append("\n<investigator-weapons name=\"")
                    .append(escape(card.getCharacter().getName()))
                    .append("\">");
            appendWeapons(result, card.getWeapons());
            result.append("\n</investigator-weapons>");
        }
        if (result.length() == "<investigator-weapon-states>".length()) {
            return "<investigator-weapon-states />";
        }
        return result.append("\n</investigator-weapon-states>")
                .toString();
    }

    private void appendOtherInvestigator(
            StringBuilder result, CharacterCardVO card) {
        CocCharacter character = card.getCharacter();
        result.append("\n<other-investigator name=\"")
                .append(escape(character.getName())).append('"');
        if (character.getParticipantId() != null) {
            result.append(" participant-id=\"")
                    .append(character.getParticipantId()).append('"');
        }
        result.append('>');
        result.append("\n属性：");
        appendCanonicalAttributes(result, character);
        result.append("\n资源：HP=")
                .append(pair(character.getHpCurrent(), character.getHpMax()))
                .append("，SAN=")
                .append(pair(character.getSanCurrent(), character.getSanMax()))
                .append("，MP=")
                .append(pair(character.getMpCurrent(), character.getMpMax()))
                .append("，幸运=").append(value(character.getLuckCurrent()))
                .append("，护甲=").append(value(character.getArmor()));
        appendConfiguredSkills(result, card.getSkills());
        appendCharacterStatuses(result, character);
        result.append("\n</other-investigator>");
    }

    private void appendCanonicalAttributes(
            StringBuilder result, CocCharacter character) {
        result.append("STR=").append(value(character.getStr()))
                .append("，CON=").append(value(character.getCon()))
                .append("，SIZ=").append(value(character.getSiz()))
                .append("，DEX=").append(value(character.getDex()))
                .append("，APP=").append(value(character.getApp()))
                .append("，INT=").append(value(character.getIntValue()))
                .append("，POW=").append(value(character.getPow()))
                .append("，EDU=").append(value(character.getEdu()));
    }

    private void appendConfiguredSkills(
            StringBuilder result, List<CocCharacterSkill> skills) {
        if (skills == null || skills.isEmpty()) {
            return;
        }
        StringJoiner values = new StringJoiner("，");
        skills.stream()
                .filter(skill -> StringUtils.hasText(skill.getDisplayName()))
                .filter(skill -> skill.getValue() != null)
                .filter(skill -> Boolean.TRUE.equals(skill.getIsCustom())
                        || skill.getBaseValue() == null
                        || skill.getValue() > skill.getBaseValue())
                .sorted(java.util.Comparator.comparing(
                        CocCharacterSkill::getDisplayName,
                        String.CASE_INSENSITIVE_ORDER))
                .forEach(skill -> values.add(
                        escape(skill.getDisplayName().trim())
                                + "=" + skill.getValue()));
        if (values.length() > 0) {
            result.append("\n技能：").append(values);
        }
    }

    private void appendCharacterStatuses(
            StringBuilder result, CocCharacter character) {
        StringJoiner statuses = new StringJoiner("；");
        addStatus(statuses, character.getMajorWound(), "重伤");
        addStatus(statuses, character.getUnconscious(), "昏迷");
        addStatus(statuses, character.getDying(), "濒死");
        addStatus(statuses, character.getDead(), "死亡");
        addStatus(statuses, character.getTemporaryInsanity(), "临时疯狂");
        if (statuses.length() > 0) {
            result.append("\n状态：").append(statuses);
        }
    }

    private void addStatus(
            StringJoiner statuses, Boolean enabled, String name) {
        if (Boolean.TRUE.equals(enabled)) {
            statuses.add(name);
        }
    }

    public String formatActiveNpcs(List<CharacterCardVO> cards) {
        if (cards == null || cards.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder("<active-npcs>");
        for (CharacterCardVO card : cards) {
            if (card == null || card.getCharacter() == null) {
                continue;
            }
            appendActiveNpc(result, card);
        }
        if (result.length() == "<active-npcs>".length()) {
            return "";
        }
        return result.append("\n</active-npcs>").toString();
    }

    private void appendActiveNpc(
            StringBuilder result, CharacterCardVO card) {
        CocCharacter character = card.getCharacter();
        result.append("\n<active-npc name=\"")
                .append(escape(character.getName())).append("\">");
        appendCompactLine(result, "职业", character.getOccupation(), 80);
        CocCharacterProfile profile = card.getProfile();
        if (profile != null) {
            appendCompactLine(result, "外貌", profile.getAppearance(), 180);
            appendCompactLine(result, "特征", profile.getTraits(), 180);
            appendCompactLine(result, "动机", profile.getIdeology(), 180);
            appendCompactLine(result, "相关地点",
                    profile.getMeaningfulLocations(), 140);
            appendCompactLine(result, "要点", profile.getNotes(), 240);
        }
        result.append("\n机械：HP ")
                .append(value(character.getHpCurrent())).append('/')
                .append(value(character.getHpMax()));
        appendMechanicalValue(result, "MP", character.getMpCurrent(),
                character.getMpMax());
        appendMechanicalValue(result, "DEX", character.getDex());
        appendMechanicalValue(result, "CON", character.getCon());
        appendMechanicalValue(result, "护甲", character.getArmor());
        appendMechanicalValue(result, "体格", character.getBuild());
        appendMechanicalText(result, "DB", character.getDamageBonus());
        appendMechanicalValue(result, "MOV", character.getMov());
        appendSparseSkills(result, card.getSkills());
        appendWeapons(result, card.getWeapons());
        result.append("\n</active-npc>");
    }

    private void appendCompactLine(
            StringBuilder result, String label, String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        String compact = text.trim().replaceAll("\\s+", " ");
        if (compact.length() > maxLength) {
            compact = compact.substring(0, maxLength) + "…";
        }
        result.append("\n").append(label).append("：")
                .append(escape(compact));
    }

    private void appendMechanicalValue(
            StringBuilder result, String label, Integer value) {
        if (value != null) {
            result.append("；").append(label).append(' ').append(value);
        }
    }

    private void appendMechanicalValue(
            StringBuilder result, String label,
            Integer current, Integer maximum) {
        if (current != null || maximum != null) {
            result.append("；").append(label).append(' ')
                    .append(value(current)).append('/').append(value(maximum));
        }
    }

    private void appendMechanicalText(
            StringBuilder result, String label, String value) {
        if (StringUtils.hasText(value)) {
            result.append("；").append(label).append(' ')
                    .append(escape(value.trim()));
        }
    }

    private void appendSparseSkills(
            StringBuilder result, List<CocCharacterSkill> skills) {
        if (skills == null) {
            return;
        }
        StringJoiner values = new StringJoiner("，");
        skills.stream()
                .filter(skill -> StringUtils.hasText(
                        skill.getDisplayName()))
                .filter(skill -> skill.getValue() != null)
                .filter(skill -> Boolean.TRUE.equals(skill.getIsCustom())
                        || skill.getBaseValue() == null
                        || !skill.getValue().equals(skill.getBaseValue()))
                .sorted(java.util.Comparator.comparing(
                        CocCharacterSkill::getDisplayName,
                        String.CASE_INSENSITIVE_ORDER))
                .forEach(skill -> values.add(
                        escape(skill.getDisplayName().trim())
                                + "=" + skill.getValue()));
        if (values.length() > 0) {
            result.append("\n特长：").append(values);
        }
    }

    private void appendWeapons(
            StringBuilder result, List<CocCharacterWeapon> weapons) {
        if (weapons == null || weapons.isEmpty()) {
            return;
        }
        StringJoiner values = new StringJoiner("；");
        for (CocCharacterWeapon weapon : weapons) {
            if (!StringUtils.hasText(weapon.getName())) {
                continue;
            }
            StringBuilder value = new StringBuilder(
                    escape(weapon.getName().trim()));
            if (StringUtils.hasText(weapon.getSkillName())) {
                value.append('/').append(escape(
                        weapon.getSkillName().trim()));
            }
            if (StringUtils.hasText(weapon.getDamage())) {
                value.append(" 伤害").append(escape(
                        weapon.getDamage().trim()));
            }
            if (weapon.getRemainingAmmo() != null
                    || weapon.getAmmoCapacity() != null) {
                value.append(" 弹药")
                        .append(value(weapon.getRemainingAmmo()))
                        .append('/')
                        .append(value(weapon.getAmmoCapacity()));
            }
            value.append(" 状态").append(
                    Boolean.TRUE.equals(weapon.getIsBroken())
                            ? "损坏" : "正常");
            values.add(value.toString());
        }
        if (values.length() > 0) {
            result.append("\n武器：").append(values);
        }
    }

    private boolean hasChangedRuntimeState(CocDiceCharacterVO card) {
        return !java.util.Objects.equals(card.hpCurrent(), card.hpMax())
                || Boolean.TRUE.equals(card.majorWound())
                || Boolean.TRUE.equals(card.unconscious())
                || Boolean.TRUE.equals(card.dying())
                || Boolean.TRUE.equals(card.dead())
                || Boolean.TRUE.equals(card.temporaryInsanity());
    }

    private void appendInlineStatuses(
            StringBuilder result, CocDiceCharacterVO card) {
        StringJoiner statuses = statuses(card);
        if (statuses.length() > 0) {
            result.append("；").append(statuses);
        }
    }

    private String format(
            List<CocDiceCharacterVO> cards,
            String containerName,
            String cardName,
            boolean includeParticipant) {
        if (cards == null || cards.isEmpty()) {
            return "<" + containerName + " />";
        }
        StringBuilder result = new StringBuilder("<")
                .append(containerName).append(">");
        for (CocDiceCharacterVO card : cards) {
            result.append("\n<").append(cardName).append(" name=\"")
                    .append(escape(card.name())).append('"');
            if (includeParticipant) {
                result.append(" participant-id=\"")
                        .append(card.participantId() == null
                                ? "player" : card.participantId())
                        .append('"');
            }
            result.append('>');
            result.append("\nHP：").append(value(card.hpCurrent())).append('/')
                    .append(value(card.hpMax()));
            result.append("；SAN：").append(value(card.sanCurrent())).append('/')
                    .append(value(card.sanMax()));
            result.append("；CON：").append(value(card.con()));
            result.append("；护甲：").append(value(card.armor()));
            appendCheckValues(result, card.checkValues());
            appendStatuses(result, card);
            result.append("\n</").append(cardName).append('>');
        }
        return result.append("\n</").append(containerName).append('>')
                .toString();
    }

    private void appendCheckValues(StringBuilder result, Map<String, Integer> checkValues) {
        if (checkValues == null || checkValues.isEmpty()) {
            return;
        }
        StringJoiner values = new StringJoiner("，");
        checkValues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> values.add(entry.getKey() + "=" + entry.getValue()));
        result.append("\n检定值：").append(values);
    }

    private void appendStatuses(StringBuilder result, CocDiceCharacterVO card) {
        StringJoiner statuses = statuses(card);
        if (statuses.length() > 0) {
            result.append("\n状态：").append(statuses);
        }
    }

    private StringJoiner statuses(CocDiceCharacterVO card) {
        StringJoiner statuses = new StringJoiner("；");
        if (Boolean.TRUE.equals(card.majorWound())) {
            statuses.add("重伤");
        }
        if (Boolean.TRUE.equals(card.unconscious())) {
            statuses.add("昏迷");
        }
        if (Boolean.TRUE.equals(card.dying())) {
            statuses.add("濒死");
        }
        if (Boolean.TRUE.equals(card.dead())) {
            statuses.add("死亡");
        }
        if (Boolean.TRUE.equals(card.temporaryInsanity())) {
            statuses.add(insanityStatus(card));
        }
        return statuses;
    }

    private String insanityStatus(CocDiceCharacterVO card) {
        String status;
        try {
            status = "临时疯狂：" + InsanityCatalog.display(card.temporaryInsanityPhase());
        } catch (IllegalArgumentException exception) {
            status = "临时疯狂（编号：" + card.temporaryInsanityPhase() + "）";
        }
        if (card.temporaryInsanityRemainingHours() != null) {
            status += "，剩余" + card.temporaryInsanityRemainingHours() + "小时";
        }
        return status;
    }

    private String value(Integer value) {
        return value == null ? "未知" : value.toString();
    }

    private String pair(Integer current, Integer maximum) {
        return value(current) + "/" + value(maximum);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
