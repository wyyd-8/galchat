package com.me.galchat.service.impl.character;

import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocCharacterSkill;
import com.me.galchat.domain.po.CocSkillDef;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class CharacterSkillResolver {

    public List<CocCharacterSkill> normalizeOverrides(
            CocCharacter character,
            List<CocCharacterSkill> candidates,
            List<CocSkillDef> definitions) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, CocSkillDef> definitionsByName = definitionsByName(definitions);
        return candidates.stream()
                .filter(skill -> skill != null && skill.getValue() != null)
                .filter(skill -> !Objects.equals(
                        skill.getValue(),
                        baseValue(skill, character, definitionsByName)))
                .toList();
    }

    public List<CocCharacterSkill> resolveEffectiveSkills(
            CocCharacter character,
            List<CocCharacterSkill> overrides,
            List<CocSkillDef> definitions) {
        Map<String, CocCharacterSkill> effective = new LinkedHashMap<>();
        if (definitions != null) {
            for (CocSkillDef definition : definitions) {
                Integer base = resolveDefaultValue(definition, character);
                if (definition == null || definition.getName() == null || base == null) {
                    continue;
                }
                effective.put(definition.getName(), fromDefinition(definition, base));
            }
        }
        if (overrides != null) {
            for (CocCharacterSkill override : overrides) {
                if (override != null && override.getDisplayName() != null
                        && override.getValue() != null) {
                    effective.put(override.getDisplayName(), override);
                }
            }
        }
        return List.copyOf(effective.values());
    }

    public int resolveBaseValue(
            CocSkillDef definition, CocCharacter character) {
        return Objects.requireNonNullElse(resolveDefaultValue(definition, character), 0);
    }

    public Integer resolveDefaultValue(
            CocSkillDef definition, CocCharacter character) {
        if (definition == null) {
            return null;
        }
        if (definition.getBaseValue() != null) {
            return definition.getBaseValue();
        }
        if (character == null) {
            return null;
        }
        return switch (definition.getBaseFormula() == null
                ? "" : definition.getBaseFormula().toUpperCase()) {
            case "DEX/2" -> character.getDex() == null
                    ? null : character.getDex() / 2;
            case "EDU" -> character.getEdu();
            default -> null;
        };
    }

    public CocSkillDef findDefinition(
            String skillName, Map<String, CocSkillDef> definitions) {
        String normalizedName = normalizeSkillName(skillName);
        if (normalizedName == null) {
            return null;
        }
        CocSkillDef exact = definitions.get(normalizedName);
        if (exact != null) {
            return exact;
        }
        int separator = normalizedName.indexOf(':');
        if (separator < 0) {
            return null;
        }
        CocSkillDef specialization = definitions.get(
                normalizedName.substring(separator + 1));
        return specialization != null
                ? specialization : definitions.get(normalizedName.substring(0, separator));
    }

    public String normalizeSkillName(String skillName) {
        if (skillName == null) {
            return null;
        }
        return skillName.trim().replace('：', ':');
    }

    public String resolveCanonicalSkillName(
            String skillName, Map<String, CocSkillDef> definitions) {
        String normalizedName = normalizeSkillName(skillName);
        if (normalizedName == null || definitions == null) {
            return normalizedName;
        }
        CocSkillDef exact = definitions.get(normalizedName);
        if (exact != null) {
            return normalizeSkillName(exact.getName());
        }
        if (normalizedName.contains(":")) {
            return normalizedName;
        }
        CocSkillDef uniqueMatch = null;
        for (Map.Entry<String, CocSkillDef> entry : definitions.entrySet()) {
            String definedName = normalizeSkillName(entry.getKey());
            int separator = definedName == null ? -1 : definedName.lastIndexOf(':');
            if (separator < 0
                    || !normalizedName.equals(definedName.substring(separator + 1))) {
                continue;
            }
            if (uniqueMatch != null && uniqueMatch != entry.getValue()) {
                return normalizedName;
            }
            uniqueMatch = entry.getValue();
        }
        return uniqueMatch == null
                ? normalizedName : normalizeSkillName(uniqueMatch.getName());
    }

    public Map<String, CocSkillDef> definitionsByName(
            List<CocSkillDef> definitions) {
        Map<String, CocSkillDef> result = new LinkedHashMap<>();
        if (definitions != null) {
            for (CocSkillDef definition : definitions) {
                if (definition != null && definition.getName() != null) {
                    result.put(normalizeSkillName(definition.getName()), definition);
                }
            }
        }
        return result;
    }

    private Integer baseValue(
            CocCharacterSkill skill,
            CocCharacter character,
            Map<String, CocSkillDef> definitions) {
        CocSkillDef definition = findDefinition(skill.getDisplayName(), definitions);
        Integer defined = resolveDefaultValue(definition, character);
        return defined != null
                ? defined : Objects.requireNonNullElse(skill.getBaseValue(), 0);
    }

    private CocCharacterSkill fromDefinition(CocSkillDef definition, int base) {
        String name = definition.getName();
        String specialization = name.contains(":")
                ? name.substring(name.indexOf(':') + 1) : "";
        return new CocCharacterSkill()
                .setSkillDefId(definition.getId())
                .setDisplayName(name)
                .setCategory(definition.getCategory())
                .setSpecialization(specialization)
                .setBaseValue(base)
                .setValue(base)
                .setIsCustom(false);
    }
}
