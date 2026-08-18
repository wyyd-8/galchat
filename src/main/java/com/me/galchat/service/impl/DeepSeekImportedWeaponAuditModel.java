package com.me.galchat.service.impl;

import com.me.galchat.constant.CocWeaponCatalogConstant;
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
            List<CocCharacterWeapon> weapons,
            List<CocWeaponCatalogConstant.WeaponDefinition> catalog,
            Map<String, String> genericTypeDefaults) {
        String response = chatClient.prompt()
                .system("""
                        你负责把主动导入的《克苏鲁的呼唤》第七版调查员武器匹配到给定武器目录。
                        只根据每件导入武器的name（武器名称）进行语义匹配；不得利用或推测伤害、射程、技能、弹容量等其他字段。
                        名称具体时，选择目录中语义最接近的武器；型号、口径、动作方式等名称信息优先于时代。
                        名称只有宽泛类型且恰好出现在“固定默认映射”中时，必须使用映射指定的catalogCode，不得自行挑选其他同类武器。
                        无法可靠匹配、名称不是武器或目录没有合理候选时，catalogCode必须返回JSON的null，不要返回字符串"null"，也不要强行匹配。
                        catalogCode为null时，后端会保留原名称，使用LARGE_CLUB的其他属性兜底，并添加异常标签“未识别武器”。
                        catalogCode只能从给定目录中原样选择，不得创造或改写。每件武器必须按原weaponId返回一次。
                        后端会根据catalogCode补齐规则数据并保留原名称；你不要生成、修改或返回任何其他武器字段。
                        只输出一个JSON对象，不要Markdown或解释，格式严格为：
                        匹配成功：{"weapons":[{"weaponId":81,"catalogCode":"PISTOL_22_AUTO"}]}
                        无法匹配：{"weapons":[{"weaponId":81,"catalogCode":null}]}
                        """)
                .user(buildPrompt(character, weapons, catalog,
                        genericTypeDefaults))
                .call()
                .content();
        logger.debug("主动导入武器审核原始响应：{}", response);
        return parser.read(response, ImportedWeaponAuditModels.Response.class);
    }

    private String buildPrompt(
            CocCharacter character,
            List<CocCharacterWeapon> weapons,
            List<CocWeaponCatalogConstant.WeaponDefinition> catalog,
            Map<String, String> genericTypeDefaults) {
        List<Map<String, Object>> values = weapons.stream()
                .map(this::weaponValue)
                .toList();
        List<Map<String, String>> candidates = catalog.stream()
                .map(this::catalogValue)
                .toList();
        List<String> defaults = genericTypeDefaults.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
        return """
                时代：%s
                待匹配武器：%s
                武器目录：%s
                固定默认映射：%s
                """.formatted(
                text(character.getEra()), values, candidates, defaults);
    }

    private Map<String, Object> weaponValue(CocCharacterWeapon weapon) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("weaponId", weapon.getId());
        value.put("name", text(weapon.getName()));
        return value;
    }

    private Map<String, String> catalogValue(
            CocWeaponCatalogConstant.WeaponDefinition definition) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("catalogCode", definition.code());
        value.put("name", definition.name());
        return value;
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "未提供" : value.trim();
    }
}
