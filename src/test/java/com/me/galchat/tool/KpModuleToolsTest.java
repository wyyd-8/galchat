package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpCharacterAttributeDTOs;
import com.me.galchat.domain.dto.KpEquipmentDTOs;
import com.me.galchat.domain.dto.KpWeaponStateDTOs;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.TrpgMaterialService;
import com.me.galchat.service.impl.TrpgEquipmentService;
import com.me.galchat.service.impl.TrpgModuleQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KpModuleToolsTest {

    @Test
    void stateChangingToolsAcceptNamesAndContinueKpResponse()
            throws Exception {
        TrpgMaterialService materialService =
                mock(TrpgMaterialService.class);
        ICharacterCardService characterCardService =
                mock(ICharacterCardService.class);
        TrpgEquipmentService equipmentService =
                mock(TrpgEquipmentService.class);
        KpModuleTools tools = new KpModuleTools(
                mock(TrpgModuleQueryService.class),
                materialService,
                characterCardService,
                equipmentService);
        ToolContext context = kpContext();
        when(materialService.showMaterial(
                7L, 41L, "玛德琳的信"))
                .thenReturn(new TrpgMaterialService.DisplayResult(
                        true, null));

        tools.showMaterial("玛德琳的信", context);
        tools.updateQuickNotes(
                "林恩", "已经感染第一阶段", context);
        KpCharacterAttributeDTOs.Adjustments adjustments =
                new KpCharacterAttributeDTOs.Adjustments(
                        10, null, null, null,
                        -5, null, null, null);
        tools.adjustBasicAttributes("林恩", adjustments, context);
        KpWeaponStateDTOs.Update weaponUpdate =
                new KpWeaponStateDTOs.Update(3, true);
        tools.updateWeaponState(
                "林恩", "左轮手枪", weaponUpdate, context);
        tools.stashWeapon("林恩", "左轮手枪",
                KpEquipmentDTOs.StashReason.DISCARDED, context);
        tools.equipWeaponFromStash(901L, "周远", context);
        KpEquipmentDTOs.PurchaseRequest purchaseRequest =
                new KpEquipmentDTOs.PurchaseRequest(java.util.List.of(
                        new KpEquipmentDTOs.PurchaseEntry(
                                "林恩", KpEquipmentDTOs.PurchaseType.ITEM,
                                "手电筒")));
        tools.purchaseEquipment(purchaseRequest, context);

        verify(materialService).showMaterial(
                7L, 41L, "玛德琳的信");
        verify(characterCardService).updateQuickNotes(
                7L, "林恩", "已经感染第一阶段");
        verify(characterCardService).adjustBasicAttributes(
                7L, "林恩", adjustments);
        verify(characterCardService).updateWeaponState(
                7L, "林恩", "左轮手枪", weaponUpdate);
        verify(equipmentService).stashWeapon(
                7L, "林恩", "左轮手枪",
                KpEquipmentDTOs.StashReason.DISCARDED);
        verify(equipmentService).equipWeaponFromStash(
                7L, 901L, "周远");
        verify(equipmentService).purchaseEquipment(
                7L, purchaseRequest);
        assertThat(KpModuleTools.class.getMethod(
                        "showMaterial", String.class, ToolContext.class)
                .getAnnotation(Tool.class).returnDirect()).isFalse();
        assertThat(KpModuleTools.class.getMethod(
                        "updateQuickNotes", String.class,
                        String.class, ToolContext.class)
                .getAnnotation(Tool.class).returnDirect()).isFalse();
        assertThat(KpModuleTools.class.getMethod(
                        "adjustBasicAttributes", String.class,
                        KpCharacterAttributeDTOs.Adjustments.class,
                        ToolContext.class)
                .getAnnotation(Tool.class).returnDirect()).isFalse();
        assertThat(KpModuleTools.class.getMethod(
                        "updateWeaponState", String.class, String.class,
                        KpWeaponStateDTOs.Update.class, ToolContext.class)
                .getAnnotation(Tool.class).returnDirect()).isFalse();
        assertThat(KpModuleTools.class.getMethod(
                        "stashWeapon", String.class, String.class,
                        KpEquipmentDTOs.StashReason.class,
                        ToolContext.class)
                .getAnnotation(Tool.class).returnDirect()).isFalse();
        assertThat(KpModuleTools.class.getMethod(
                        "equipWeaponFromStash", Long.class,
                        String.class, ToolContext.class)
                .getAnnotation(Tool.class).returnDirect()).isFalse();
    }

    @Test
    void modelFacingModuleToolsExposeNoIdArguments() {
        assertThat(Arrays.stream(KpModuleTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .filter(method -> !method.getName().equals(
                        "equipWeaponFromStash"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .filter(type -> !ToolContext.class.equals(type)))
                .doesNotContain(Long.class, Long.TYPE);
    }

    @Test
    void equipmentToolsExposeStashIdOnlyWhenTakingAWeaponOut() {
        assertThat(Arrays.stream(KpModuleTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(method -> method.getAnnotation(Tool.class).name()))
                .contains("stashWeapon", "equipWeaponFromStash",
                        "purchaseEquipment");

        assertThat(Arrays.stream(KpModuleTools.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(
                        "equipWeaponFromStash"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .containsExactly(Long.class, String.class,
                        ToolContext.class);
    }

    @Test
    void basicAttributeToolSchemaUsesCocIntFieldName() {
        String schema = Arrays.stream(ToolCallbacks.from(
                        new KpModuleTools(null, null, null)))
                .filter(callback -> callback.getToolDefinition().name()
                        .equals("adjustBasicAttributes"))
                .findFirst()
                .orElseThrow()
                .getToolDefinition()
                .inputSchema();

        assertThat(schema)
                .contains("\"str\"", "\"int\"", "\"edu\"")
                .doesNotContain("\"intValue\"");
    }

    private ToolContext kpContext() {
        return new ToolContext(Map.of(
                ChatToolContextConstant.ACTOR_TYPE_KEY,
                GroupChatConstant.ACTOR_KP,
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY,
                7L,
                ChatToolContextConstant.USER_WORLD_ID_KEY,
                5L,
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY,
                41L));
    }
}
