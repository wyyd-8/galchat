package com.me.galchat.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.UserCharacterInfo;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.mapper.UserCharacterInfoMapper;
import com.me.galchat.mapper.UserWorldPrefixMapper;
import com.me.galchat.service.impl.user.UserCharacterInfoServiceImpl;
import com.me.galchat.service.impl.world.UserWorldPrefixServiceImpl;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateImageReadTest {

    @Mock private UserWorldPrefixMapper worldMapper;
    @Mock private UserCharacterInfoMapper characterMapper;
    @Mock private IWorldTemplateService worldTemplates;
    @Mock private ICharacterTemplateService characterTemplates;
    @Mock private IUserWorldPrefixService worldAuth;
    @InjectMocks private UserWorldPrefixServiceImpl worlds;
    @InjectMocks private UserCharacterInfoServiceImpl characters;

    @BeforeEach
    void initializeMetadata() {
        MybatisPlusTestSupport.initialize(UserWorldPrefix.class, UserCharacterInfo.class,
                WorldTemplate.class, CharacterTemplate.class);
        ReflectionTestUtils.setField(worlds, "baseMapper", worldMapper);
        ReflectionTestUtils.setField(characters, "baseMapper", characterMapper);
        ReflectionTestUtils.setField(worlds, "entityClass", UserWorldPrefix.class);
        ReflectionTestUtils.setField(characters, "entityClass", UserCharacterInfo.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "old-world.png")
    void existingWorldListUsesCurrentTemplateImage(String storedImage) {
        UserWorldPrefix world = world(storedImage);
        when(worldMapper.selectList(any())).thenReturn(List.of(world));
        when(worldTemplates.list(any(Wrapper.class))).thenReturn(List.of(
                new WorldTemplate().setId(20L).setImage("new-world.png")));

        var result = worlds.listBaseInfoByUserId(7L);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.getImage()).isEqualTo("new-world.png");
            assertThat(item.getName()).isEqualTo("自定义世界名称");
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "old-world.png")
    void existingWorldDetailUsesCurrentTemplateImage(String storedImage) {
        when(worldMapper.selectOne(any())).thenReturn(world(storedImage));
        when(worldTemplates.list(any(Wrapper.class))).thenReturn(List.of(
                new WorldTemplate().setId(20L).setImage("new-world.png")));

        assertThat(worlds.getUserWorld(7L, 1L).getImage()).isEqualTo("new-world.png");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "old-character.png")
    void existingCharacterUsesCurrentTemplateImageAndKeepsUserState(String storedImage) {
        when(characterMapper.selectList(any())).thenReturn(List.of(character(storedImage)));
        when(characterTemplates.list(any(Wrapper.class))).thenReturn(List.of(
                new CharacterTemplate().setId(9L).setImage("new-character.png")));

        var result = characters.listByUserWorldId(1L);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.getCharacterImage()).isEqualTo("new-character.png");
            assertThat(item.getCharacterName()).isEqualTo("已有角色");
            assertThat(item.getFavorValue()).isEqualTo(42);
            assertThat(item.getUserInfoPrompt()).isEqualTo("已有记忆");
        });
        verify(worldAuth).checkUserWorldAuth(1L, false);
    }

    @Test
    void clearingTemplateImageDoesNotRestoreOldSnapshotImage() {
        when(worldMapper.selectList(any())).thenReturn(List.of(world("old-world.png")));
        when(worldTemplates.list(any(Wrapper.class))).thenReturn(List.of(new WorldTemplate().setId(20L)));
        when(characterMapper.selectList(any())).thenReturn(List.of(character("old-character.png")));
        when(characterTemplates.list(any(Wrapper.class))).thenReturn(List.of(new CharacterTemplate().setId(9L)));

        assertThat(worlds.listBaseInfoByUserId(7L).getFirst().getImage()).isNull();
        assertThat(characters.listByUserWorldId(1L).getFirst().getCharacterImage()).isNull();
    }

    @Test
    void mapsImagesByTemplateIdAndKeepsSnapshotWhenTemplateIsMissing() {
        when(worldMapper.selectList(any())).thenReturn(List.of(
                world("old-world.png"), world("orphan-world.png").setId(2L).setWorldId(21L)));
        when(worldTemplates.list(any(Wrapper.class))).thenReturn(List.of(
                new WorldTemplate().setId(20L).setImage("new-world.png")));
        when(characterMapper.selectList(any())).thenReturn(List.of(
                character("orphan-character.png").setCharacterId(10L), character("old-character.png")));
        when(characterTemplates.list(any(Wrapper.class))).thenReturn(List.of(
                new CharacterTemplate().setId(9L).setImage("new-character.png")));

        assertThat(worlds.listBaseInfoByUserId(7L)).extracting(UserWorldPrefix::getImage)
                .containsExactly("new-world.png", "orphan-world.png");
        assertThat(characters.listByUserWorldId(1L)).extracting(UserCharacterInfo::getCharacterImage)
                .containsExactly("orphan-character.png", "new-character.png");
    }

    private UserWorldPrefix world(String image) {
        return new UserWorldPrefix().setId(1L).setUserId(7L).setWorldId(20L)
                .setName("自定义世界名称").setImage(image);
    }

    private UserCharacterInfo character(String image) {
        return new UserCharacterInfo().setUserWorldId(1L).setCharacterId(9L)
                .setCharacterName("已有角色").setCharacterImage(image)
                .setFavorValue(42).setUserInfoPrompt("已有记忆");
    }
}
