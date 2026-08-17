package com.me.galchat.constant;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static java.util.Map.entry;

/** Global weapon catalog sourced from the CoC appendix. */
public final class CocWeaponCatalogConstant {

    public enum WeaponEra {
        TWENTIES,
        MODERN,
        BOTH
    }

    public enum WeaponKind {
        MELEE,
        FIREARM,
        OTHER_RANGED
    }

    public enum AcquisitionLevel {
        COMMON("普通"),
        CONTROLLED("受管制");

        private final String label;

        AcquisitionLevel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
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
            WeaponKind kind,
            AcquisitionLevel acquisitionLevel,
            boolean autoSelectable,
            boolean canImpale,
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
            entry("WOOD_AXE", weapon("WOOD_AXE", "伐木斧", "格斗:斧", "1D8+2+DB", "接触", "1", null, null, WeaponEra.BOTH)),
            entry("PISTOL_22_AUTO", firearm(
                    "PISTOL_22_AUTO", ".22自动手枪", "射击:手枪", "1D6",
                    "10m", "1（3）", 6, "100", WeaponEra.BOTH,
                    AcquisitionLevel.COMMON, true)),
            entry("REVOLVER_32", firearm(
                    "REVOLVER_32", ".32/7.65mm左轮手枪", "射击:手枪", "1D8",
                    "15m", "1（3）", 6, "100", WeaponEra.BOTH,
                    AcquisitionLevel.COMMON, true)),
            entry("REVOLVER_38_9MM", firearm(
                    "REVOLVER_38_9MM", ".38/9mm左轮手枪", "射击:手枪", "1D10",
                    "15m", "1（3）", 6, "100", WeaponEra.BOTH,
                    AcquisitionLevel.COMMON, true)),
            entry("PISTOL_38_9MM", firearm(
                    "PISTOL_38_9MM", ".38/9mm自动手枪", "射击:手枪", "1D10",
                    "15m", "1（3）", 8, "99", WeaponEra.BOTH,
                    AcquisitionLevel.COMMON, true)),
            entry("LUGER_P08", firearm(
                    "LUGER_P08", "9mm 鲁格 P08", "射击:手枪", "1D10",
                    "15m", "1（3）", 8, "99", WeaponEra.BOTH,
                    AcquisitionLevel.COMMON, true)),
            entry("PISTOL_45_AUTO", firearm(
                    "PISTOL_45_AUTO", ".45自动手枪", "射击:手枪", "1D10+2",
                    "15m", "1（3）", 7, "100", WeaponEra.BOTH,
                    AcquisitionLevel.COMMON, true)),
            entry("RIFLE_22_BOLT", firearm(
                    "RIFLE_22_BOLT", ".22栓动步枪", "射击:步枪/霰弹枪", "1D6+1",
                    "30m", "1", 6, "99", WeaponEra.BOTH,
                    AcquisitionLevel.COMMON, true)),
            entry("RIFLE_30_LEVER", firearm(
                    "RIFLE_30_LEVER", ".30杠杆步枪", "射击:步枪/霰弹枪", "2D6",
                    "50m", "1", 6, "98", WeaponEra.BOTH,
                    AcquisitionLevel.COMMON, true)),
            entry("RIFLE_30_06_BOLT", firearm(
                    "RIFLE_30_06_BOLT", ".30-06（7.62mm）栓动步枪",
                    "射击:步枪/霰弹枪", "2D6+4", "110m", "1", 5, "100",
                    WeaponEra.BOTH, AcquisitionLevel.COMMON, true)),
            entry("SHOTGUN_12_DOUBLE", firearm(
                    "SHOTGUN_12_DOUBLE", "12号双管霰弹枪", "射击:步枪/霰弹枪",
                    "近4D6；中2D6；远1D6", "近≤10m；中≤20m；远≤50m",
                    "1或2", 2, "100", WeaponEra.BOTH,
                    AcquisitionLevel.COMMON, true)),
            entry("DERRINGER_25", firearm(
                    "DERRINGER_25", ".25德林杰手枪（单管）", "射击:手枪", "1D6",
                    "3m", "1", 1, "100", WeaponEra.TWENTIES,
                    AcquisitionLevel.COMMON, true)),
            entry("SHOTGUN_12_SAWED_OFF", firearm(
                    "SHOTGUN_12_SAWED_OFF", "12号锯短双管霰弹枪",
                    "射击:步枪/霰弹枪", "近4D6；中1D6；远无效",
                    "近≤5m；中≤10m；远无效", "1或2", 2, "100",
                    WeaponEra.TWENTIES, AcquisitionLevel.CONTROLLED, false)),
            entry("THOMPSON_SMG", firearm(
                    "THOMPSON_SMG", "汤普森冲锋枪", "射击:冲锋枪", "1D10+2",
                    "20m", "1或全自动", 20, "96", WeaponEra.TWENTIES,
                    AcquisitionLevel.CONTROLLED, false)),
            entry("REVOLVER_357", firearm(
                    "REVOLVER_357", ".357马格南左轮手枪", "射击:手枪",
                    "1D8+1D4", "15m", "1（3）", 6, "100", WeaponEra.MODERN,
                    AcquisitionLevel.COMMON, true)),
            entry("GLOCK_17", firearm(
                    "GLOCK_17", "9mm 格洛克17", "射击:手枪", "1D10",
                    "15m", "1（3）", 17, "98", WeaponEra.MODERN,
                    AcquisitionLevel.COMMON, true)),
            entry("SHOTGUN_12_PUMP", firearm(
                    "SHOTGUN_12_PUMP", "12号泵动式霰弹枪", "射击:步枪/霰弹枪",
                    "近4D6；中2D6；远1D6", "近≤10m；中≤20m；远≤50m",
                    "1", 5, "100", WeaponEra.MODERN,
                    AcquisitionLevel.COMMON, true)),
            entry("AK_47", firearm(
                    "AK_47", "AK-47/AKM", "射击:步枪/霰弹枪", "2D6+1",
                    "100m", "1（2）或全自动", 30, "100", WeaponEra.MODERN,
                    AcquisitionLevel.CONTROLLED, false)),
            entry("M4", firearm(
                    "M4", "M4", "射击:步枪/霰弹枪", "2D6",
                    "90m", "1或3发点射", 30, "97", WeaponEra.MODERN,
                    AcquisitionLevel.CONTROLLED, false)),
            entry("MP5", firearm(
                    "MP5", "H&K MP5", "射击:冲锋枪", "1D10",
                    "20m", "1（2）或全自动", 30, "97", WeaponEra.MODERN,
                    AcquisitionLevel.CONTROLLED, false))
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

    public static List<WeaponDefinition> autoSelectableForEra(String era) {
        return availableForEra(era).stream()
                .filter(WeaponDefinition::autoSelectable)
                .toList();
    }

    public static List<WeaponDefinition> weaponsByKind(WeaponKind kind) {
        return WEAPONS.values().stream()
                .filter(weapon -> weapon.kind() == kind)
                .sorted(java.util.Comparator.comparing(WeaponDefinition::code))
                .toList();
    }

    public static WeaponDefinition require(String code) {
        WeaponDefinition definition = code == null ? null : WEAPONS.get(code);
        if (definition == null) {
            throw new IllegalArgumentException("未知武器：" + code);
        }
        return definition;
    }

    public static Optional<WeaponDefinition> findByExactName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = name.trim();
        return WEAPONS.values().stream()
                .filter(weapon -> weapon.name().equals(normalized))
                .findFirst();
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
                inferKind(code, requiredSkillName), AcquisitionLevel.COMMON,
                true, Set.of(
                        "BOW", "CROSSBOW", "HAND_AXE",
                        "LARGE_KNIFE", "MEDIUM_KNIFE", "SMALL_KNIFE",
                        "LANCE", "LARGE_SWORD", "MEDIUM_SWORD",
                        "LIGHT_SWORD", "WOOD_AXE").contains(code),
                false, List.of(), null);
    }

    private static WeaponDefinition abnormalWeapon(
            String code, String name, String requiredSkillName,
            String damage, String range, String attacksPerRound,
            Integer ammoCapacity, String malfunction, WeaponEra era,
            List<String> riskTags) {
        return new WeaponDefinition(code, name, requiredSkillName, damage,
                range, attacksPerRound, ammoCapacity, malfunction, era,
                inferKind(code, requiredSkillName), AcquisitionLevel.COMMON,
                true, false, true, List.copyOf(riskTags), null);
    }

    private static WeaponDefinition firearm(
            String code, String name, String requiredSkillName,
            String damage, String range, String attacksPerRound,
            Integer ammoCapacity, String malfunction, WeaponEra era,
            AcquisitionLevel acquisitionLevel, boolean autoSelectable) {
        return new WeaponDefinition(code, name, requiredSkillName, damage,
                range, attacksPerRound, ammoCapacity, malfunction, era,
                WeaponKind.FIREARM, acquisitionLevel, autoSelectable,
                true, false, List.of(), null);
    }

    private static WeaponKind inferKind(
            String code, String requiredSkillName) {
        if (Set.of("BOW", "CROSSBOW", "TASER").contains(code)) {
            return WeaponKind.OTHER_RANGED;
        }
        if (requiredSkillName != null
                && requiredSkillName.startsWith("射击:")) {
            return WeaponKind.FIREARM;
        }
        return WeaponKind.MELEE;
    }
}
