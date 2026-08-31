package com.me.galchat.domain.dto;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KpDiceRequestDTOsTest {

    @Test
    void everyToolRequestRecordComponentHasDescription() {
        List<String> undocumentedComponents = Arrays.stream(
                        KpDiceRequestDTOs.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .flatMap(recordType -> Arrays.stream(recordType.getDeclaredFields())
                        .filter(field -> !field.isSynthetic())
                        .filter(field -> lacksDescription(field))
                        .map(field -> recordType.getSimpleName() + "." + field.getName()))
                .sorted()
                .toList();

        assertThat(undocumentedComponents).isEmpty();
    }

    @Test
    void healingRequestExposesARecoveryModeChoice() {
        assertThat(KpDiceRequestDTOs.Healing.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("reason", "sourceMode", "mode", "targets");
    }

    @Test
    void damageRequestOnlyExposesStandaloneDamageFields() {
        assertThat(KpDiceRequestDTOs.Damage.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("reason", "targets");
        assertThat(KpDiceRequestDTOs.DamageTarget.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("targetCharacterName", "formula");
    }

    @Test
    void checkTargetAcceptsMultipleCandidateCheckNames() {
        assertThat(KpDiceRequestDTOs.CheckTarget.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "characterName", "checkNames", "modifier", "modifierReason");
    }

    private boolean lacksDescription(Field field) {
        ToolParam toolParam = field.getAnnotation(ToolParam.class);
        return toolParam == null || toolParam.description().isBlank();
    }
}
