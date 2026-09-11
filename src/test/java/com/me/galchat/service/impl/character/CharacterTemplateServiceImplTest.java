package com.me.galchat.service.impl.character;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.mapper.CharacterTemplateMapper;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CharacterTemplateServiceImplTest {

    @Test
    void createPreservesCocPlayStyle() {
        IWorldTemplateService worldTemplateService =
                mock(IWorldTemplateService.class);
        CharacterTemplateServiceImpl service = spy(
                new CharacterTemplateServiceImpl(
                        worldTemplateService));
        when(worldTemplateService.getById(20L)).thenReturn(
                new WorldTemplate().setId(20L).setAuthorId(1L));
        AtomicReference<CharacterTemplate> saved =
                new AtomicReference<>();
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return true;
        }).when(service).save(any(CharacterTemplate.class));

        service.createCharacterTemplate(
                1L, 20L,
                new CharacterTemplate()
                        .setName("角色")
                        .setCocPlayStyle("偏好稳健调查"));

        assertThat(saved.get().getCocPlayStyle())
                .isEqualTo("偏好稳健调查");
    }

    @Test
    void updatePreservesCocPlayStyle() {
        MybatisPlusTestSupport.initialize(CharacterTemplate.class);
        IWorldTemplateService worldTemplateService =
                mock(IWorldTemplateService.class);
        CharacterTemplateMapper characterTemplateMapper =
                mock(CharacterTemplateMapper.class);
        CharacterTemplateServiceImpl service = spy(
                new CharacterTemplateServiceImpl(
                        worldTemplateService));
        ReflectionTestUtils.setField(
                service, "baseMapper", characterTemplateMapper);
        when(worldTemplateService.getById(20L)).thenReturn(
                new WorldTemplate().setId(20L).setAuthorId(1L));
        doReturn(new CharacterTemplate().setId(9L))
                .when(service)
                .getCharacterTemplateByWorldId(20L, 9L);
        when(characterTemplateMapper.update(
                isNull(), any(Wrapper.class))).thenReturn(1);

        service.updateCharacterTemplate(
                1L, 20L, 9L,
                new CharacterTemplate()
                        .setName("角色")
                        .setCocPlayStyle("偏好主动承担风险"));

        var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(characterTemplateMapper).update(
                isNull(), captor.capture());
        assertThat(((AbstractWrapper<?, ?, ?>) captor.getValue())
                .getParamNameValuePairs().values())
                .contains("偏好主动承担风险");
    }
}
