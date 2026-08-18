package com.me.galchat.service.impl;

import com.me.galchat.constant.CocWeaponCatalogConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterProfile;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocCharacterWeapon;
import com.me.galchat.exception.UserRequestException;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CharacterCardImportParser {

    private static final Pattern BASIC_INFO = Pattern.compile("^([^，,]+)[，,]\\s*([^，,]+)[，,]\\s*([^，,]+)[，,]\\s*(\\d+)岁$");
    private static final Pattern LOCATION = Pattern.compile("^出身(.+?)[，,]\\s*现居(.+)$");
    private static final Pattern ERA = Pattern.compile("^时代[:：]\\s*(.+?)(?:\\s+玩家[:：].*)?$");
    private static final Pattern ATTRIBUTE = Pattern.compile("\\b(STR|CON|SIZ|DEX|APP|INT|POW|EDU)\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILL = Pattern.compile("^(.+?)\\s+(\\d+)%\\s*(?:\\([^)]*\\))?(?:[，,]\\s*伤害\\s*(.+))?$");
    private static final Pattern LABELED = Pattern.compile("^([^：:]+)[：:]\\s*(.*)$");

    private CharacterCardImportParser() {
    }

    static ParsedCharacterCard parse(String text) {
        if (!StringUtils.hasText(text)) {
            throw new UserRequestException("人物卡文本不能为空");
        }
        List<String> lines = text.lines().map(String::trim).toList();
        CocCharacter character = new CocCharacter();
        CocCharacterProfile profile = new CocCharacterProfile();
        Map<String, CocCharacterSkill> skills = new LinkedHashMap<>();
        List<CocCharacterWeapon> weapons = new ArrayList<>();
        Map<String, Integer> attributes = new LinkedHashMap<>();
        String section = "HEADER";
        List<String> equipment = new ArrayList<>();
        List<String> assets = new ArrayList<>();

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String detected = detectSection(line);
            if (detected != null) {
                section = detected;
                continue;
            }
            switch (section) {
                case "HEADER" -> parseHeader(line, character, attributes);
                case "COMBAT" -> parseCombat(line, skills, weapons);
                case "SKILL" -> parseSkill(line, skills);
                case "BACKGROUND" -> parseBackground(line, profile);
                case "EQUIPMENT" -> equipment.add(line);
                case "ASSET" -> parseAsset(line, profile, assets);
                default -> { }
            }
        }

        requireBasicInfo(character, attributes);
        setAttributes(character, attributes);
        validateAttributeTotal(character);
        CharacterCardRules.DerivedValues derived = CharacterCardRules.derive(
                character.getStr(), character.getCon(), character.getSiz(), character.getDex(),
                character.getPow(), character.getAge());
        int mythos = skills.containsKey("克苏鲁神话") ? skills.get("克苏鲁神话").getValue() : 0;
        character.setDamageBonus(derived.damageBonus())
                .setBuild(derived.build())
                .setMov(derived.mov())
                .setHpCurrent(derived.hp()).setHpMax(derived.hp())
                .setSanCurrent(derived.san()).setSanMax(Math.max(0, 99 - mythos))
                .setMpCurrent(derived.mp()).setMpMax(derived.mp())
                .setLuckCurrent(null)
                .setArmor(0).setMajorWound(false).setUnconscious(false).setDying(false)
                .setDead(false).setTemporaryInsanity(false);
        profile.setEquipmentText(joinLines(equipment));
        if (!assets.isEmpty()) {
            profile.setAssetsText(joinLines(assets));
        }
        return new ParsedCharacterCard(character, new ArrayList<>(skills.values()), weapons, profile);
    }

    private static void parseHeader(String line, CocCharacter character, Map<String, Integer> attributes) {
        Matcher basic = BASIC_INFO.matcher(line);
        if (basic.matches()) {
            character.setName(basic.group(1).trim()).setOccupation(basic.group(2).trim())
                    .setSex(basic.group(3).trim()).setAge(Integer.parseInt(basic.group(4)));
            return;
        }
        Matcher location = LOCATION.matcher(line);
        if (location.matches()) {
            character.setBirthplace(location.group(1).trim()).setResidence(location.group(2).trim());
            return;
        }
        Matcher era = ERA.matcher(line);
        if (era.matches()) {
            character.setEra(era.group(1).trim());
            return;
        }
        Matcher matcher = ATTRIBUTE.matcher(line);
        while (matcher.find()) {
            attributes.put(matcher.group(1).toUpperCase(), Integer.parseInt(matcher.group(2)));
        }
    }

    private static void parseCombat(String line, Map<String, CocCharacterSkill> skills,
                                    List<CocCharacterWeapon> weapons) {
        Matcher matcher = SKILL.matcher(line);
        if (!matcher.matches()) {
            return;
        }
        String name = matcher.group(1).trim();
        int value = Integer.parseInt(matcher.group(2));
        if (StringUtils.hasText(matcher.group(3))) {
            boolean canImpale = CocWeaponCatalogConstant
                    .findByExactName(name)
                    .map(CocWeaponCatalogConstant.WeaponDefinition::canImpale)
                    .orElse(false);
            weapons.add(new CocCharacterWeapon().setName(name)
                    .setDamage(matcher.group(3).trim())
                    .setCanImpale(canImpale).setIsBroken(false));
        } else {
            skills.putIfAbsent(name, skill(name, value));
        }
    }

    private static void parseSkill(String line, Map<String, CocCharacterSkill> skills) {
        Matcher matcher = SKILL.matcher(line);
        if (matcher.matches()) {
            String name = matcher.group(1).trim();
            skills.put(name, skill(name, Integer.parseInt(matcher.group(2))));
        }
    }

    private static CocCharacterSkill skill(String name, int value) {
        String category = name.contains(":") ? name.substring(0, name.indexOf(':')) : null;
        String specialization = name.contains(":") ? name.substring(name.indexOf(':') + 1) : "";
        return new CocCharacterSkill().setDisplayName(name).setCategory(category)
                .setSpecialization(specialization).setValue(value).setIsCustom(false);
    }

    private static void parseBackground(String line, CocCharacterProfile profile) {
        Matcher matcher = LABELED.matcher(line);
        if (!matcher.matches()) {
            if (!line.startsWith("（") && !line.startsWith("(")) {
                profile.setNotes(appendLine(profile.getNotes(), line));
            }
            return;
        }
        String value = matcher.group(2).trim();
        switch (matcher.group(1).trim()) {
            case "形象描述" -> profile.setAppearance(value);
            case "思想与信念" -> profile.setIdeology(value);
            case "重要之人" -> profile.setSignificantPeople(value);
            case "意义非凡之地" -> profile.setMeaningfulLocations(value);
            case "宝贵之物" -> profile.setTreasuredPossessions(value);
            case "特质" -> profile.setTraits(value);
            case "伤口和疤痕", "创伤和疤痕" -> profile.setInjuriesAndScars(value);
            case "恐惧症和狂躁症", "恐惧症和躁狂症" -> profile.setPhobiasAndManias(value);
            default -> profile.setNotes(appendLine(profile.getNotes(), line));
        }
    }

    private static void parseAsset(String line, CocCharacterProfile profile, List<String> assets) {
        Matcher matcher = LABELED.matcher(line);
        if (matcher.matches() && matcher.group(1).trim().equals("消费水平")) {
            profile.setSpendingLevel(matcher.group(2).trim());
        } else if (matcher.matches() && matcher.group(1).trim().equals("现金")) {
            profile.setCash(matcher.group(2).trim());
        } else {
            assets.add(line);
        }
    }

    private static String detectSection(String line) {
        if (!line.contains("—") && !line.contains("-")) {
            return null;
        }
        if (line.contains("战斗")) return "COMBAT";
        if (line.contains("技能")) return "SKILL";
        if (line.contains("背景故事")) return "BACKGROUND";
        if (line.contains("装备和道具")) return "EQUIPMENT";
        if (line.contains("资产")) return "ASSET";
        return line.replace("—", "").replace("-", "").isBlank() ? "END" : null;
    }

    private static void requireBasicInfo(CocCharacter character, Map<String, Integer> attributes) {
        if (!StringUtils.hasText(character.getName()) || character.getAge() == null) {
            throw new UserRequestException("人物卡首行格式不正确");
        }
        if (character.getAge() < 15 || character.getAge() > 90) {
            throw new UserRequestException("调查员年龄必须在15到90岁之间");
        }
        for (String name : List.of("STR", "CON", "SIZ", "DEX", "APP", "INT", "POW", "EDU")) {
            Integer value = attributes.get(name);
            if (value == null) {
                throw new UserRequestException("人物卡缺少" + name + "属性");
            }
            CharacterCardRules.validateAttribute(name, value);
        }
    }

    private static void setAttributes(CocCharacter character, Map<String, Integer> values) {
        character.setStr(values.get("STR")).setCon(values.get("CON")).setSiz(values.get("SIZ"))
                .setDex(values.get("DEX")).setApp(values.get("APP")).setIntValue(values.get("INT"))
                .setPow(values.get("POW")).setEdu(values.get("EDU"));
    }

    private static void validateAttributeTotal(CocCharacter character) {
        int total = character.getStr() + character.getCon() + character.getSiz() + character.getDex()
                + character.getApp() + character.getIntValue() + character.getPow() + character.getEdu();
        if (total > 460) {
            throw new UserRequestException("角色卡属性总和不能超过460，当前为" + total);
        }
    }

    private static String joinLines(List<String> lines) {
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static String appendLine(String oldValue, String line) {
        return oldValue == null ? line : oldValue + "\n" + line;
    }

    record ParsedCharacterCard(CocCharacter character, List<CocCharacterSkill> skills,
                               List<CocCharacterWeapon> weapons, CocCharacterProfile profile) {
    }
}
