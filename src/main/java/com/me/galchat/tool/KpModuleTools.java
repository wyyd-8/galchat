package com.me.galchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.KpCharacterAttributeDTOs;
import com.me.galchat.domain.dto.KpEquipmentDTOs;
import com.me.galchat.domain.dto.KpWeaponStateDTOs;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.ICharacterCardService;
import com.me.galchat.service.impl.TrpgMaterialService;
import com.me.galchat.service.impl.TrpgEquipmentService;
import com.me.galchat.service.impl.TrpgModuleQueryService;
import com.me.galchat.utils.TypeConvertUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class KpModuleTools {

    private final TrpgModuleQueryService queryService;
    private final TrpgMaterialService materialService;
    private final ICharacterCardService characterCardService;
    private final TrpgEquipmentService equipmentService;

    @Autowired
    public KpModuleTools(
            TrpgModuleQueryService queryService,
            TrpgMaterialService materialService,
            ICharacterCardService characterCardService,
            TrpgEquipmentService equipmentService) {
        this.queryService = queryService;
        this.materialService = materialService;
        this.characterCardService = characterCardService;
        this.equipmentService = equipmentService;
    }

    public KpModuleTools(
            TrpgModuleQueryService queryService,
            TrpgMaterialService materialService,
            ICharacterCardService characterCardService) {
        this(queryService, materialService, characterCardService, null);
    }

    @Tool(
            name = "readModuleLocation",
            description = "按地点标题索引中的准确名称读取该地点完整模组原文。")
    public TrpgModuleQueryService.LocationResult readLocation(
            @ToolParam(description = "地点准确名称，不能传ID")
            String locationName,
            ToolContext context) {
        KpContext kp = requireKpContext(context);
        return queryService.readLocation(
                kp.conversationId(), locationName);
    }

    @Tool(
            name = "readModuleClue",
            description = "按线索标题索引中的准确名称读取线索完整原文。")
    public TrpgModuleQueryService.ClueResult readClue(
            @ToolParam(description = "线索准确标题，不能传ID")
            String clueTitle,
            ToolContext context) {
        KpContext kp = requireKpContext(context);
        return queryService.readClue(kp.conversationId(), clueTitle);
    }

    @Tool(
            name = "readModuleMaterial",
            description = "按材料标题索引中的准确名称读取材料文字介绍和展示状态，不展示图片。")
    public TrpgModuleQueryService.MaterialResult readMaterial(
            @ToolParam(description = "材料准确标题，不能传ID")
            String materialTitle,
            ToolContext context) {
        KpContext kp = requireKpContext(context);
        return queryService.readMaterial(
                kp.conversationId(), materialTitle);
    }

    @Tool(
            name = "showMaterial",
            description = "按准确材料标题向玩家展示图片。调用后仍必须继续回复具体消息。")
    public String showMaterial(
            @ToolParam(description = "材料准确标题，不能传ID")
            String materialTitle,
            ToolContext context) {
        KpContext kp = requireKpContext(context);
        TrpgMaterialService.DisplayResult result =
                materialService.showMaterial(
                        kp.conversationId(), kp.replyStepId(),
                        materialTitle);
        return result.shown()
                ? "材料已展示。请继续向调查员说明这份材料为何在此刻出现以及他们能观察到什么。"
                : "该材料此前已经展示。请继续回复调查员的当前行动。";
    }

    @Tool(
            name = "updateQuickNotes",
            description = "按人物卡准确名称更新KP私有快速笔记，可用于调查员或NPC状态。")
    public String updateQuickNotes(
            @ToolParam(description = "调查员或NPC的人物卡准确名称，不能传ID")
            String characterName,
            @ToolParam(description = "覆盖保存的KP私有快速笔记；空文本表示清除")
            String quickNotes,
            ToolContext context) {
        KpContext kp = requireKpContext(context);
        characterCardService.updateQuickNotes(
                kp.runId(), characterName, quickNotes);
        return "快速笔记已更新。";
    }

    @Tool(
            name = "adjustBasicAttributes",
            description = """
                    按人物卡准确名称增量修正调查员或NPC的八项基础属性。
                    只填写需要修改的字段；结果会限制在0到100，并自动重算DB与build。
                    其他人物卡属性和状态保持不变。调用后仍必须继续回复具体消息。
                    """)
    public KpCharacterAttributeDTOs.Result adjustBasicAttributes(
            @ToolParam(description = "调查员或NPC的人物卡准确名称，不能传ID")
            String characterName,
            @ToolParam(description = "需要修改的基础属性及整数修正值")
            KpCharacterAttributeDTOs.Adjustments adjustments,
            ToolContext context) {
        KpContext kp = requireKpContext(context);
        return characterCardService.adjustBasicAttributes(
                kp.runId(), characterName, adjustments);
    }

    @Tool(
            name = "updateWeaponState",
            description = """
                    按准确人物卡名称和武器名称提交武器的最新状态。
                    仅用于装填、修理检定成功后的状态恢复，或不经过枪械攻击工具的明确状态修正。
                    requestFirearmAttack 已自动处理弹药消耗、贯穿和故障；调用该枪械攻击工具后，禁止再调用本工具覆盖结果。
                    常规逐发装填占用完整主动位且最多增加两发，不能超过武器容量；装填一发并立即射击时只增加一发，随后射击需手动合并一颗惩罚骰。调用后仍必须继续完成当前裁定。
                    """)
    public KpWeaponStateDTOs.Result updateWeaponState(
            @ToolParam(description = "人物卡准确名称，不能传ID")
            String characterName,
            @ToolParam(description = "该人物卡持有的准确武器名称，不能传ID")
            String weaponName,
            @ToolParam(description = "需要覆盖保存的剩余弹药和/或损坏状态")
            KpWeaponStateDTOs.Update update,
            ToolContext context) {
        KpContext kp = requireKpContext(context);
        return characterCardService.updateWeaponState(
                kp.runId(), characterName, weaponName, update);
    }

    @Tool(
            name = "stashWeapon",
            description = "将人物卡当前持有的准确武器原样移入武器暂存库，并自动记录当前场景地点。用于主动丢弃、被打落或被夺取。")
    public KpEquipmentDTOs.StashResult stashWeapon(
            @ToolParam(description = "原持有者的人物卡准确名称，不能传ID")
            String characterName,
            @ToolParam(description = "该人物卡持有的准确武器名称，不能传ID")
            String weaponName,
            @ToolParam(description = "DISCARDED主动丢弃、DISARMED被打落、SEIZED被夺取")
            KpEquipmentDTOs.StashReason reason,
            ToolContext context) {
        KpContext kp = requireKpContext(context);
        return equipmentService.stashWeapon(
                kp.runId(), characterName, weaponName, reason);
    }

    @Tool(
            name = "equipWeaponFromStash",
            description = "按武器暂存ID取出武器，并为指定人物卡重新装备。武器原有弹药、损坏和其他状态保持不变。")
    public KpEquipmentDTOs.EquipResult equipWeaponFromStash(
            @ToolParam(description = "KP上下文中武器暂存库展示的武器ID")
            Long weaponId,
            @ToolParam(description = "重新装备该武器的人物卡准确名称，不能传人物卡ID")
            String targetCharacterName,
            ToolContext context) {
        KpContext kp = requireKpContext(context);
        return equipmentService.equipWeaponFromStash(
                kp.runId(), weaponId, targetCharacterName);
    }

    @Tool(
            name = "purchaseEquipment",
            description = "一次为一个或多个人物卡添加购买到的武器或普通物品。不校验价格、资金、时代、库存或技能；武器必须使用战斗技能规则所列的准确名称。")
    public KpEquipmentDTOs.PurchaseResult purchaseEquipment(
            @ToolParam(description = "本次购买后各人物卡实际获得的全部武器和物品")
            KpEquipmentDTOs.PurchaseRequest request,
            ToolContext context) {
        KpContext kp = requireKpContext(context);
        return equipmentService.purchaseEquipment(kp.runId(), request);
    }

    private KpContext requireKpContext(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new UserRequestException("KP模组工具上下文不存在");
        }
        Map<String, Object> values = context.getContext();
        if (!GroupChatConstant.ACTOR_KP.equals(
                TypeConvertUtils.asString(values.get(
                        ChatToolContextConstant.ACTOR_TYPE_KEY)))) {
            throw new UserAuthException("只有KP可以调用模组工具");
        }
        Long conversationId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY));
        Long replyStepId = TypeConvertUtils.asLong(values.get(
                ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY));
        if (conversationId == null || replyStepId == null) {
            throw new UserRequestException("KP模组工具缺少群聊、跑团或回复步骤上下文");
        }
        return new KpContext(
                conversationId, conversationId, replyStepId);
    }

    private record KpContext(
            Long conversationId, Long runId, Long replyStepId) {
    }
}
