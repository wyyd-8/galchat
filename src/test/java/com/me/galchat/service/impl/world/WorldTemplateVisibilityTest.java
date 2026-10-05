package com.me.galchat.service.impl.world;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.WorldTemplateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorldTemplateVisibilityTest {
    @Test
    void publicTemplateCannotBecomePrivate() { rejectsRetraction(true); }

    @Test
    void legacyPublicTemplateCannotBecomePrivate() { rejectsRetraction(null); }

    private void rejectsRetraction(Boolean visible) {
        var f = fixture(visible);
        assertThatThrownBy(() -> f.service.updateWorldTemplate(1L, 20L, edit(false)))
                .isInstanceOf(UserRequestException.class).hasMessageContaining("公开");
        verify(f.mapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void privateTemplateCanBePublished() { assertUpdate(false, true, true); }

    @Test
    void omittedVisibilityKeepsPrivateTemplatePrivate() { assertUpdate(false, null, false); }

    @Test
    void omittedVisibilityKeepsPublicTemplatePublic() { assertUpdate(true, null, true); }

    @Test
    void privateSaveCannotUndoPublicationByAnotherEditor() {
        var f = fixture(false);
        when(f.mapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> f.service.updateWorldTemplate(1L, 20L, edit(false)))
                .isInstanceOf(UserRequestException.class).hasMessageContaining("公开");
    }

    private void assertUpdate(Boolean previous, Boolean requested, boolean expected) {
        var f = fixture(previous);
        doAnswer(invocation -> {
            var parameters = new HashMap<String, Object>();
            parameters.put("et", null);
            parameters.put("ew", invocation.getArgument(1));
            var sql = f.configuration.getMappedStatement(WorldTemplateMapper.class.getName() + ".update")
                    .getBoundSql(parameters);
            String text = sql.getSql().replaceAll("\\s+", " ");
            assertThat(text).contains("visible=");
            var columns = text.substring(text.indexOf("SET") + 3, text.indexOf("WHERE")).split(",");
            int index = java.util.stream.IntStream.range(0, columns.length)
                    .filter(i -> columns[i].trim().startsWith("visible")).findFirst().orElseThrow();
            var value = f.configuration.newMetaObject(parameters)
                    .getValue(sql.getParameterMappings().get(index).getProperty());
            assertThat(value).isEqualTo(expected);
            if (!expected) {
                // The database must guard the transition too, after the ownership read.
                assertThat(text.substring(text.indexOf("WHERE"))).contains("visible =");
            }
            return 1;
        }).when(f.mapper).update(isNull(), any(Wrapper.class));
        f.service.updateWorldTemplate(1L, 20L, edit(requested));
        verify(f.mapper).update(isNull(), any(Wrapper.class));
    }

    private static WorldTemplate edit(Boolean visible) {
        return new WorldTemplate().setName("新名称").setBackground("新背景").setVisible(visible);
    }

    private Fixture fixture(Boolean visible) {
        var config = new MybatisConfiguration();
        config.addMapper(WorldTemplateMapper.class);
        var mapper = mock(WorldTemplateMapper.class);
        var service = new WorldTemplateServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectById(20L)).thenReturn(new WorldTemplate().setId(20L).setAuthorId(1L).setVisible(visible));
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        return new Fixture(service, mapper, config);
    }

    private record Fixture(WorldTemplateServiceImpl service, WorldTemplateMapper mapper, MybatisConfiguration configuration) {}
}
