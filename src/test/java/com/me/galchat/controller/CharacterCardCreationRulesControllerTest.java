package com.me.galchat.controller;

import com.me.galchat.domain.dto.StepwiseCharacterCardModels;
import com.me.galchat.service.impl.StepwiseCharacterCardCreationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CharacterCardCreationRulesControllerTest {

    @Test
    void exposesCreationRulesOutsideTheDraftResource() {
        StepwiseCharacterCardCreationService service =
                mock(StepwiseCharacterCardCreationService.class);
        var rules = new StepwiseCharacterCardModels.RulesView(
                1, List.of(), List.of(), List.of(), List.of("1920S", "MODERN"));
        when(service.getRules()).thenReturn(rules);

        var controller = new CharacterCardCreationRulesController(service);

        assertThat(controller.getRules().getData()).isSameAs(rules);
    }
}
