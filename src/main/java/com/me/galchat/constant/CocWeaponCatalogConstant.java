package com.me.galchat.constant;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/** Global auto-generation allowlist sourced from the CoC appendix. */
public final class CocWeaponCatalogConstant {

    public enum WeaponEra {
        TWENTIES,
        MODERN,
        BOTH
    }

    public record WeaponDefinition(
            String code,
            String name,
            String requiredSkillName,
            String damage,
            String range,
            String attacksPerRound,
            Integer ammoCapacity,
            String malfunction,
            WeaponEra era,
            boolean abnormal,
            List<String> riskTags,
            String notes) {
    }

    private static final Map<String, WeaponDefinition> WEAPONS = Map.ofEntries(
            entry("BOW", weapon("BOW", "弓箭", "射击:弓", "1D6+半DB", "30m", "1", 1, "97", WeaponEra.BOTH)),
            entry("BRASS_KNUCKLES", weapon("BRASS_KNUCKLES", "黄铜指虎", "斗殴", "1D3+1+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("WHIP", weapon("WHIP", "长鞭", "格斗:鞭", "1D3+半DB", "3m", "1", null, null, WeaponEra.TWENTIES)),
            entry("CHAINSAW", abnormalWeapon(
                    "CHAINSAW", "链锯", "格斗:链锯", "2D8", "接触", "1",
                    null, "95", WeaponEra.MODERN,
                    List.of("显眼", "高噪声", "笨重", "破坏现场"))),
            entry("SAP", weapon("SAP", "包革金属棒（大头棍、护身棒）", "斗殴", "1D8+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("LARGE_CLUB", weapon("LARGE_CLUB", "大型棍棒（棒球棒、板球棒、拨火棍）", "斗殴", "1D8+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("POLICE_BATON", weapon("POLICE_BATON", "小型棍棒（警棍）", "斗殴", "1D6+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("CROSSBOW", weapon("CROSSBOW", "弩", "射击:弓", "1D8+2", "50m", "1/2", 1, "96", WeaponEra.BOTH)),
            entry("GARROTE", weapon("GARROTE", "绞索", "格斗:绞索", "1D6+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("HAND_AXE", weapon("HAND_AXE", "手斧/镰刀", "格斗:斧", "1D6+1+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("LARGE_KNIFE", weapon("LARGE_KNIFE", "大型刀具（骑兵军刀等）", "斗殴", "1D8+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("MEDIUM_KNIFE", weapon("MEDIUM_KNIFE", "中型刀具（切肉刀等）", "斗殴", "1D4+2+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("SMALL_KNIFE", weapon("SMALL_KNIFE", "小型刀具（折叠刀等）", "斗殴", "1D4+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("TEAR_GAS_SPRAY", weapon("TEAR_GAS_SPRAY", "催泪喷雾", "斗殴", "眩晕", "2m", "1", null, null, WeaponEra.MODERN)),
            entry("NUNCHAKU", weapon("NUNCHAKU", "双节棍", "格斗:连枷", "1D8+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("LANCE", weapon("LANCE", "矛（骑枪）", "格斗:矛", "1D8+1", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("LARGE_SWORD", weapon("LARGE_SWORD", "大型刀剑（马刀）", "格斗:刀剑", "1D8+1+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("MEDIUM_SWORD", weapon("MEDIUM_SWORD", "中型刀剑（长剑、重剑）", "格斗:刀剑", "1D6+1+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("LIGHT_SWORD", weapon("LIGHT_SWORD", "轻型刀剑（花剑、剑杖）", "格斗:刀剑", "1D6+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("STUN_GUN", weapon("STUN_GUN", "电击器", "斗殴", "1D3+眩晕", "接触", "1", null, "97", WeaponEra.MODERN)),
            entry("TASER", weapon("TASER", "泰瑟枪", "射击:手枪", "1D3+眩晕", "5m", "1", 3, "95", WeaponEra.MODERN)),
            entry("WOOD_AXE", weapon("WOOD_AXE", "伐木斧", "格斗:斧", "1D8+2+DB", "接触", "1", null, null, WeaponEra.BOTH))
    );

    private CocWeaponCatalogConstant() {
    }

    public static Map<String, WeaponDefinition> weapons() {
        return WEAPONS;
    }

    public static List<WeaponDefinition> availableForEra(String era) {
        WeaponEra normalized = normalizeEra(era);
        return WEAPONS.values().stream()
                .filter(weapon -> weapon.era() == WeaponEra.BOTH
                        || weapon.era() == normalized)
                .sorted(java.util.Comparator.comparing(WeaponDefinition::code))
                .toList();
    }

    public static WeaponDefinition require(String code) {
        WeaponDefinition definition = code == null ? null : WEAPONS.get(code);
        if (definition == null) {
            throw new IllegalArgumentException("未知常规武器：" + code);
        }
        return definition;
    }

    private static WeaponEra normalizeEra(String era) {
        if (era == null || era.isBlank()) {
            return WeaponEra.BOTH;
        }
        String normalized = era.trim().toLowerCase(Locale.ROOT);
        if (Set.of("1920", "1920s", "20s", "1920年代", "二十年代")
                .contains(normalized)) {
            return WeaponEra.TWENTIES;
        }
        if (normalized.equals("现代") || normalized.equals("modern")
                || normalized.equals("现代社会")) {
            return WeaponEra.MODERN;
        }
        return WeaponEra.BOTH;
    }

    private static WeaponDefinition weapon(
            String code, String name, String requiredSkillName,
            String damage, String range, String attacksPerRound,
            Integer ammoCapacity, String malfunction, WeaponEra era) {
        return new WeaponDefinition(code, name, requiredSkillName, damage,
                range, attacksPerRound, ammoCapacity, malfunction, era,
                false, List.of(), null);
    }

    private static WeaponDefinition abnormalWeapon(
            String code, String name, String requiredSkillName,
            String damage, String range, String attacksPerRound,
            Integer ammoCapacity, String malfunction, WeaponEra era,
            List<String> riskTags) {
        return new WeaponDefinition(code, name, requiredSkillName, damage,
                range, attacksPerRound, ammoCapacity, malfunction, era,
                true, List.copyOf(riskTags), null);
    }
}
