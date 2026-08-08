package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.vo.CharacterCardVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeepSeekCharacterCardGenerationModel
        implements CharacterCardGenerationModel {

    private static final Logger logger = LoggerFactory.getLogger(
            DeepSeekCharacterCardGenerationModel.class);

    private final ChatClient chatClient;
    private final CharacterCardGenerationResponseParser parser;

    public DeepSeekCharacterCardGenerationModel(
            @Qualifier("groupNonThinkingChatClient") ChatClient chatClient,
            CharacterCardGenerationResponseParser parser) {
        this.chatClient = chatClient;
        this.parser = parser;
    }

    @Override
    public CharacterCardGenerationModels.BuildPlan generateBuild(
            CharacterTemplate template,
            CocModule module,
            List<String> availableSkills) {
        String response = chatClient.prompt()
                .system("""
                        你负责按《克苏鲁的呼唤》第七版快速开始规则设计调查员。
                        输入中的人格原型只用于提取性格内核与行为倾向。你必须在模组时代、地点和社会背景中重新创作一位原生的新调查员，
                        不是原型角色本人，也不是其穿越、转生、召唤、失忆或化名后的延续。不得沿用原型角色的姓名、原世界专有名词、人物关系或事实经历。
                        必须为新调查员生成符合模组时代与文化背景的新姓名、出生地、居住地、职业和本世界经历基础。
                        只输出一个JSON对象，不要Markdown或解释。不得自行计算属性值和技能值，只做排序。
                        attributeOrder必须恰好包含STR、CON、SIZ、DEX、APP、INT、POW、EDU各一次，顺序代表由高到低。
                        occupationSkillOrder必须从可选技能中选9项且不重复，顺序代表由高到低。“信用评级”可以不进入本职技能排序，
                        此时系统会为它设置保底值10；不要把“信用评级”放入interestSkillOrder。
                        本职技能排序会直接赋值：第1项=70，第2至3项=60，第4至6项=50，第7至9项=40。
                        对一般技能而言，70代表核心专长，60代表熟练强项，50代表稳定的职业水准，40代表受过基础职业训练。
                        “信用评级”不是工作熟练度，而是财富、生活水平与社会地位：40属于标准生活水平；50、60、70属于小康，
                        数值越高表示越富裕、社会资源越多。请依据调查员在其职业中的收入和阶层决定信用评级的位置，不要只按技能重要性排列它。
                        interestSkillOrder必须从可选技能中给出至少6项候选且不重复，不得与本职技能重合；系统只采用前4项有效候选。
                        年龄必须为15到90的整数。职业、基本信息和排序应优先贴合性格内核、玩法偏好与模组背景。
                        输出字段严格为：name, age, sex, birthplace, residence, occupation, attributeOrder,
                        occupationSkillOrder, interestSkillOrder, explanations。explanations是简短字符串数组。
                        """)
                .user(buildPrompt(template, module, availableSkills))
                .call()
                .content();
        logger.debug("AI调查员基础阶段原始响应：{}", response);
        return parser.read(response, CharacterCardGenerationModels.BuildPlan.class);
    }

    @Override
    public CharacterCardGenerationModels.BackgroundPlan generateBackground(
            CharacterTemplate template,
            CocModule module,
            CharacterCardVO card,
            CharacterCardGenerationModels.BackgroundRolls rolls,
            List<CharacterCardGenerationModels.AvailableWeapon> weapons) {
        String response = chatClient.prompt()
                .system("""
                        你负责为《克苏鲁的呼唤》第七版调查员撰写人物背景。
                        这位调查员是模组世界中土生土长的全新人物，人格原型只提供性格内核与行为倾向。
                        不得把调查员写成原型角色本人或其穿越、转生、召唤、失忆、化名后的延续，也不得添加来自其他世界的记忆、身份、人物关系或专有名词。
                        所有经历、关系、地点和物品必须从已确定的调查员身份与模组世界自然生长出来。
                        只输出一个JSON对象，不要Markdown或解释。六项背景骰结果只是宽松的创作方向，可以为人物一致性做局部调整。
                        appearance、ideology、significantPeople、meaningfulLocations、treasuredPossessions、traits都必须填写。
                        keyConnectionCategory只能从IDEOLOGY、SIGNIFICANT_PEOPLE、MEANINGFUL_LOCATIONS、TREASURED_POSSESSIONS、TRAITS中选择，
                        keyConnectionText必须与所选背景条目一致。
                        weaponCode只能为候选武器code之一或null。equipment为0到5件符合职业、时代和背景的常规装备，不包含所选武器。
                        输出字段严格为：appearance, ideology, significantPeople, meaningfulLocations,
                        treasuredPossessions, traits, keyConnectionCategory, keyConnectionText, weaponCode, equipment。
                        """)
                .user(backgroundPrompt(template, module, card, rolls, weapons))
                .call()
                .content();
        logger.debug("AI调查员背景阶段原始响应：{}", response);
        return parser.read(response, CharacterCardGenerationModels.BackgroundPlan.class);
    }

    private String buildPrompt(
            CharacterTemplate template,
            CocModule module,
            List<String> availableSkills) {
        return """
                人格原型（只参考下列抽象特征，不代表模组内存在此人）：
                - 性格核心：%s
                - 跑团玩法偏好：%s
                模组资料：
                - 名称：%s
                - 时代：%s
                - 公开简介：%s
                - 调查员创建要求：%s
                可选技能（必须逐字使用）：%s
                """.formatted(
                text(template.getPersonality()), text(template.getCocPlayStyle()),
                module == null ? "未提供" : text(module.getName()),
                module == null ? "未提供" : text(module.getEra()),
                module == null ? "未提供" : text(module.getIntroduction()),
                module == null ? "未提供" : text(module.getInvestigatorCreation()),
                availableSkills);
    }

    private String backgroundPrompt(
            CharacterTemplate template,
            CocModule module,
            CharacterCardVO card,
            CharacterCardGenerationModels.BackgroundRolls rolls,
            List<CharacterCardGenerationModels.AvailableWeapon> weapons) {
        Map<String, Integer> attributes = new LinkedHashMap<>();
        attributes.put("STR", card.getCharacter().getStr());
        attributes.put("CON", card.getCharacter().getCon());
        attributes.put("SIZ", card.getCharacter().getSiz());
        attributes.put("DEX", card.getCharacter().getDex());
        attributes.put("APP", card.getCharacter().getApp());
        attributes.put("INT", card.getCharacter().getIntValue());
        attributes.put("POW", card.getCharacter().getPow());
        attributes.put("EDU", card.getCharacter().getEdu());
        List<String> strongestSkills = card.getSkills().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(12)
                .map(this::formatSkill)
                .toList();
        return """
                已生成的模组内调查员：%s
                人格原型（仅参考抽象特征）：性格核心：%s；玩法偏好：%s
                模组：%s；时代：%s；公开简介：%s；调查员创建要求：%s
                已确定信息：年龄%s，性别%s，职业%s，出生地%s，居住地%s
                属性：%s
                主要技能：%s
                六项背景骰与方向：%s
                可选武器（已附当前技能值，只能使用code）：%s
                """.formatted(
                text(card.getCharacter().getName()),
                text(template.getPersonality()), text(template.getCocPlayStyle()),
                module == null ? "未提供" : text(module.getName()),
                module == null ? "未提供" : text(module.getEra()),
                module == null ? "未提供" : text(module.getIntroduction()),
                module == null ? "未提供" : text(module.getInvestigatorCreation()),
                card.getCharacter().getAge(), text(card.getCharacter().getSex()),
                text(card.getCharacter().getOccupation()), text(card.getCharacter().getBirthplace()),
                text(card.getCharacter().getResidence()), attributes, strongestSkills,
                rolls.directions(), weapons);
    }

    private String formatSkill(CocCharacterSkill skill) {
        return skill.getDisplayName() + "=" + skill.getValue();
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "未提供" : value.trim();
    }
}
