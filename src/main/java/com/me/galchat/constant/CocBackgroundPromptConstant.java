package com.me.galchat.constant;

import com.me.galchat.domain.dto.CharacterCardGenerationModels;

import java.util.List;
import java.util.Map;

public final class CocBackgroundPromptConstant {

    private static final List<String> IDEOLOGY = List.of(
            "崇拜一位大能并向其祈祷", "没有宗教也能过得很好", "科学终将解释一切",
            "相信命运", "某个协会或秘密结社的成员", "认为社会上的某种罪恶应被根除",
            "相信神秘学", "拥有明确政治立场", "金钱就是力量", "积极投身某种社会运动");
    private static final List<String> PERSON = List.of(
            "父母", "祖父母", "兄弟姐妹", "子女", "伴侣",
            "教授其最高本职技能的人", "儿时的朋友", "一位名人",
            "调查员同伴", "模组中的NPC");
    private static final List<String> PERSON_REASON = List.of(
            "感激对方曾提供帮助", "对方教会了其重要事物", "对方赋予其生命意义",
            "亏欠对方并想寻求谅解", "与对方有共同经历", "希望向对方证明自己",
            "崇拜对方", "对对方感到后悔", "想证明自己比对方更好", "曾受对方迫害并想报复");
    private static final List<String> LOCATION = List.of(
            "学习的地方", "故乡", "邂逅初恋的地方", "供其静思的地方", "社交场所",
            "与思想信念有关的地方", "重要之人的坟墓", "家", "一生中最幸福时所在的地方", "工作场所");
    private static final List<String> POSSESSION = List.of(
            "与最高技能有关的物品", "职业必备物品", "儿时纪念品", "逝者遗物",
            "重要之人赠送的东西", "收藏品", "来历未知且正在寻找答案的物品",
            "体育用品", "一件武器", "一只宠物");
    private static final List<String> TRAIT = List.of(
            "慷慨大方", "动物之友", "梦想家", "享乐主义", "赌徒或敢于冒险",
            "料理能手", "万人迷", "义薄云天", "名声在外", "野心勃勃");

    private CocBackgroundPromptConstant() {
    }

    public static CharacterCardGenerationModels.BackgroundRolls rolls(
            int ideology, int person, int reason,
            int location, int possession, int trait) {
        return new CharacterCardGenerationModels.BackgroundRolls(
                ideology, person, reason, location, possession, trait,
                Map.of(
                        "ideology", at(IDEOLOGY, ideology),
                        "significantPersonWho", at(PERSON, person),
                        "significantPersonReason", at(PERSON_REASON, reason),
                        "meaningfulLocation", at(LOCATION, location),
                        "treasuredPossession", at(POSSESSION, possession),
                        "trait", at(TRAIT, trait)));
    }

    private static String at(List<String> values, int roll) {
        if (roll < 1 || roll > 10) {
            throw new IllegalArgumentException("背景骰必须在1到10之间");
        }
        return values.get(roll - 1);
    }
}
