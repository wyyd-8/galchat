package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.ImportedWeaponAuditModels;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterWeapon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeepSeekImportedWeaponAuditModel
        implements ImportedWeaponAuditModel {

    private static final Logger logger = LoggerFactory.getLogger(
            DeepSeekImportedWeaponAuditModel.class);

    private final ChatClient chatClient;
    private final CharacterCardGenerationResponseParser parser;

    public DeepSeekImportedWeaponAuditModel(
            @Qualifier("groupNonThinkingChatClient") ChatClient chatClient,
            CharacterCardGenerationResponseParser parser) {
        this.chatClient = chatClient;
        this.parser = parser;
    }

    @Override
    public ImportedWeaponAuditModels.Response review(
            CocCharacter character,
            List<CocCharacterWeapon> weapons) {
        String response = chatClient.prompt()
                .system("""
                        你负责审核主动导入的《克苏鲁的呼唤》第七版调查员武器是否可能妨碍探索。
                        只审核输入中的武器，不修改名称、技能、伤害或其他人物卡数据。每件武器必须按原weaponId返回一次。
                        abnormal=true表示该武器相对人物所处时代与常见调查场景，存在明显的携带、使用或规则数据风险；否则为false。
                        风险包括但不限于：过于显眼、噪声过大、笨重、严格管制、难以隐藏、妨碍通行、容易破坏现场或惊动目标。
                        所有武器（包括枪械）都要依据规则书第十六章武器列表，按类别粗略判断伤害是否偏高：
                        - 常规近战、弓弩与投掷武器通常为1D3至1D8，并可能附加DB、半DB或少量固定值；达到2D8或更高属于高伤害。
                        - 手枪通常为1D6至1D10+2；明显高于这一范围，或包含更多伤害骰，属于高伤害。
                        - 步枪、突击步枪与机枪单发通常为2D6至2D6+4；明显超过这一范围属于高伤害。
                        - 霰弹枪近距离通常为2D6至4D6，并随距离递减；达到近距离上沿、明显超过上沿或不随距离递减，属于高伤害。
                        - 冲锋枪单发通常为1D8至1D10+2；明显超过这一范围属于高伤害，射速和全自动不能用来提高单发伤害。
                        - 爆炸物与重武器约为2D6至4D10；达到4D10已属高伤，6D10及以上必须判定为高伤害。
                        这里判断的是探索风险，不是规则数据是否合法。规则书中正式列出的高伤害武器也必须判定为异常，不提供任何特殊武器豁免。
                        只要伤害达到所属类别的高伤端，或伤害公式明显高于该类常见范围，就必须判定abnormal=true，并加入riskTags“伤害异常”。
                        riskTags只给出1到6个简短中文标签；abnormal=false时必须返回空数组。
                        只输出一个JSON对象，不要Markdown或解释，格式严格为：
                        {"weapons":[{"weaponId":整数,"abnormal":布尔值,"riskTags":["标签"]}]}
                        """)
                .user(buildPrompt(character, weapons))
                .call()
                .content();
        logger.debug("主动导入武器审核原始响应：{}", response);
        return parser.read(response, ImportedWeaponAuditModels.Response.class);
    }

    private String buildPrompt(
            CocCharacter character,
            List<CocCharacterWeapon> weapons) {
        List<Map<String, Object>> values = weapons.stream()
                .map(this::weaponValue)
                .toList();
        return """
                调查员：%s
                时代：%s
                职业：%s
                待审核武器：%s
                """.formatted(
                text(character.getName()), text(character.getEra()),
                text(character.getOccupation()), values);
    }

    private Map<String, Object> weaponValue(CocCharacterWeapon weapon) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("weaponId", weapon.getId());
        value.put("name", text(weapon.getName()));
        value.put("skillName", text(weapon.getSkillName()));
        value.put("damage", text(weapon.getDamage()));
        value.put("range", text(weapon.getRange()));
        value.put("attacksPerRound", text(weapon.getAttacksPerRound()));
        value.put("ammoCapacity", weapon.getAmmoCapacity());
        value.put("malfunction", text(weapon.getMalfunction()));
        value.put("notes", text(weapon.getNotes()));
        return value;
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "未提供" : value.trim();
    }
}
