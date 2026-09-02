package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.CocModuleClue;
import com.me.galchat.domain.po.CocModuleContext;
import com.me.galchat.domain.po.CocModuleLocation;
import com.me.galchat.domain.po.CocModuleMaterial;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.TrpgWeaponStash;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocModuleClueMapper;
import com.me.galchat.mapper.CocModuleContextMapper;
import com.me.galchat.mapper.CocModuleLocationMapper;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.CocModuleMaterialMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.TrpgWeaponStashMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrpgModuleContextAssemblerTest {

    @Test
    void kpContextDependsOnPersistentWeaponStash() {
        assertThat(Arrays.stream(
                        TrpgModuleContextAssembler.class
                                .getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(
                        constructor.getParameterTypes())))
                .contains(com.me.galchat.mapper
                        .TrpgWeaponStashMapper.class);
    }

    @Test
    void kpContextDisplaysStashedWeaponIdLocationAndSnapshotState() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleContextMapper contextMapper =
                mock(CocModuleContextMapper.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        TrpgWeaponStashMapper stashMapper =
                mock(TrpgWeaponStashMapper.class);
        TrpgModuleContextAssembler assembler =
                new TrpgModuleContextAssembler(
                        moduleMapper, contextMapper, locationMapper,
                        mock(CocModuleClueMapper.class),
                        mock(CocModuleMaterialMapper.class),
                        mock(GroupReplyPlanMapper.class),
                        mock(CocCharacterMapper.class),
                        mock(TrpgMaterialStateStore.class),
                        stashMapper);
        GroupConversation conversation = new GroupConversation()
                .setId(7L).setModuleId(3L);
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setName("古树林中"));
        when(contextMapper.selectOne(any())).thenReturn(null);
        when(locationMapper.selectList(any())).thenReturn(List.of());
        when(stashMapper.selectList(any())).thenReturn(List.of(
                new TrpgWeaponStash()
                        .setWeaponId(901L)
                        .setRunId(7L)
                        .setSourceCharacterName("林恩")
                        .setLocationName("圣玛丽医院 - 阁楼")
                        .setStashReason("DISARMED")
                        .setWeaponSnapshot(
                                new TrpgWeaponStash.WeaponSnapshot()
                                        .setName(".38/9mm自动手枪")
                                        .setAmmoCapacity(8)
                                        .setRemainingAmmo(3)
                                        .setIsBroken(true))));

        String result = assembler.formatKpContext(conversation);

        assertThat(result)
                .contains("<weapon-stash>")
                .contains("ID=901")
                .contains(".38/9mm自动手枪")
                .contains("原持有者=林恩")
                .contains("位置=圣玛丽医院 - 阁楼")
                .contains("原因=DISARMED")
                .contains("弹药=3/8")
                .contains("状态=损坏")
                .contains("</weapon-stash>");
    }

    @Test
    void kpContextTreatsFutureModuleSectionsAsReferenceOnly() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleContextMapper contextMapper =
                mock(CocModuleContextMapper.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        TrpgModuleContextAssembler assembler =
                new TrpgModuleContextAssembler(
                        moduleMapper, contextMapper, locationMapper,
                        mock(CocModuleClueMapper.class),
                        mock(CocModuleMaterialMapper.class),
                        mock(GroupReplyPlanMapper.class),
                        mock(CocCharacterMapper.class),
                        mock(TrpgMaterialStateStore.class));
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setModuleId(3L);
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L)
                .setName("古树林中")
                .setIntroduction("进入森林搜寻人质"));
        when(contextMapper.selectOne(any())).thenReturn(
                new CocModuleContext()
                        .setTruthBackground("幕后真相")
                        .setTimeline("第三天日落后发生仪式")
                        .setEndingContent("调查员可能阻止仪式"));
        when(locationMapper.selectList(any())).thenReturn(List.of(
                new CocModuleLocation().setId(21L)
                        .setName("第一天－上午")
                        .setSummary("通报会与出发准备")
                        .setContent("当前场景正文")));

        String result = assembler.formatKpContext(conversation);

        assertThat(result)
                .contains("<module-progress-boundary-rules>")
                .contains("时间线、幕后真相、结局内容")
                .contains("下一场景开头简要介绍")
                .contains("不代表相应事件已经发生")
                .contains("不得把下一场景简介续写为当前场景事实")
                .contains("选景阶段仍可自由选择模组场景")
                .contains("</module-progress-boundary-rules>")
                .contains("""
                        <location>
                        <name>第一天－上午</name>
                        <summary>通报会与出发准备</summary>
                        </location>
                        """)
                .doesNotContain("- 第一天－上午：通报会与出发准备");
    }

    @Test
    void dynamicChildSceneUsesOnlyItsSelectedMainSceneContent() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleContextMapper contextMapper =
                mock(CocModuleContextMapper.class);
        CocModuleLocationMapper locationMapper =
                mock(CocModuleLocationMapper.class);
        CocModuleClueMapper clueMapper = mock(CocModuleClueMapper.class);
        CocModuleMaterialMapper materialMapper =
                mock(CocModuleMaterialMapper.class);
        GroupReplyPlanMapper planMapper =
                mock(GroupReplyPlanMapper.class);
        CocCharacterMapper characterMapper =
                mock(CocCharacterMapper.class);
        TrpgMaterialStateStore materialStateStore =
                mock(TrpgMaterialStateStore.class);
        TrpgModuleContextAssembler assembler =
                new TrpgModuleContextAssembler(
                        moduleMapper, contextMapper, locationMapper,
                        clueMapper, materialMapper, planMapper,
                        characterMapper, materialStateStore);
        GroupConversation conversation = new GroupConversation()
                .setId(7L)
                .setUserWorldId(5L)
                .setModuleId(3L)
                .setActiveReplyPlanId(10L);
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setName("太阳与九英镑")
                .setIntroduction("寻找失踪者"));
        when(contextMapper.selectOne(any())).thenReturn(
                new CocModuleContext()
                        .setTruthBackground("幕后真相")
                        .setTimeline("12月23日：模组开始"));
        when(locationMapper.selectList(any())).thenReturn(List.of(
                new CocModuleLocation().setId(21L).setName("医院")
                        .setSummary("患者隔离地")
                        .setContent("医院完整原文"),
                new CocModuleLocation().setId(23L)
                        .setName("医院阁楼")
                        .setSummary("封闭区域")
                        .setContent("阁楼完整原文"),
                new CocModuleLocation().setId(22L).setName("酒店")
                        .setSummary("失踪者住处")
                        .setContent("酒店完整原文")));
        when(clueMapper.selectList(any())).thenReturn(List.of(
                new CocModuleClue().setTitle("感染源")
                        .setContent("水源受污染").setImportant(true),
                new CocModuleClue().setTitle("普通传闻")
                        .setContent("酒馆闲谈").setImportant(false)));
        when(materialMapper.selectList(any())).thenReturn(List.of(
                new CocModuleMaterial().setId(31L).setTitle("玛德琳的信")
                        .setDescription("信中提到酒店")
                        .setImageUrl("https://secret.example/image.jpg")));
        when(planMapper.selectById(10L)).thenReturn(new GroupReplyPlan()
                .setId(10L).setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(21L));
        when(planMapper.selectById(11L)).thenReturn(new GroupReplyPlan()
                .setId(11L).setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setContextId(21L).setParentPlanId(10L));
        conversation.setActiveReplyPlanId(11L);
        when(characterMapper.selectList(any())).thenReturn(List.of(
                new CocCharacter().setName("林恩")
                        .setQuickNotes("已经感染第一阶段")));
        when(materialStateStore.shownIds(7L)).thenReturn(Set.of(31L));

        String result = assembler.formatKpContext(conversation);

        assertThat(result)
                .contains("太阳与九英镑", "幕后真相")
                .contains("医院", "医院阁楼", "封闭区域", "酒店")
                .contains("感染源", "普通传闻")
                .contains("医院完整原文")
                .contains("玛德琳的信", "信中提到酒店", "已展示")
                .contains("林恩", "已经感染第一阶段")
                .doesNotContain("酒店完整原文")
                .doesNotContain("阁楼完整原文")
                .doesNotContain("酒馆闲谈")
                .doesNotContain("https://secret.example/image.jpg");
    }
}
