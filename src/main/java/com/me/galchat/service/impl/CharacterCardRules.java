package com.me.galchat.service.impl;

import com.me.galchat.exception.UserRequestException;

final class CharacterCardRules {

    private CharacterCardRules() {
    }

    static DerivedValues derive(int str, int con, int siz, int dex, int pow, int age) {
        DamageBuild damageBuild = deriveDamageBuild(str, siz);
        int mov = str < siz && dex < siz ? 7 : str > siz && dex > siz ? 9 : 8;
        if (age >= 80) {
            mov -= 5;
        } else if (age >= 70) {
            mov -= 4;
        } else if (age >= 60) {
            mov -= 3;
        } else if (age >= 50) {
            mov -= 2;
        } else if (age >= 40) {
            mov -= 1;
        }
        return new DerivedValues(
                damageBuild.damageBonus(), damageBuild.build(),
                mov, (con + siz) / 10, pow, pow / 5);
    }

    static DamageBuild deriveDamageBuild(int str, int siz) {
        int total = str + siz;
        String damageBonus;
        int build;
        if (total <= 64) {
            damageBonus = "-2";
            build = -2;
        } else if (total <= 84) {
            damageBonus = "-1";
            build = -1;
        } else if (total <= 124) {
            damageBonus = "0";
            build = 0;
        } else if (total <= 164) {
            damageBonus = "+1D4";
            build = 1;
        } else if (total <= 204) {
            damageBonus = "+1D6";
            build = 2;
        } else {
            int diceCount = 2 + (total - 205) / 80;
            damageBonus = "+" + diceCount + "D6";
            build = diceCount + 1;
        }
        return new DamageBuild(damageBonus, build);
    }

    static void validateAttribute(String name, int value) {
        if (value < 10 || value > 90) {
            throw new UserRequestException(
                    name + "属性值必须在10到90之间");
        }
    }

    record DamageBuild(String damageBonus, int build) {
    }

    record DerivedValues(String damageBonus, int build, int mov, int hp, int san, int mp) {
    }
}
