package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.TrpgWeaponStash;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.service.IWorldTemplateService;
import com.me.galchat.service.impl.character.CharacterTemplateServiceImpl;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.ResultMapping;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JsonbMappingTest {
    @Test
    void characterUpdateBindsNonEmptyFavorabilityAsJson() throws Exception {
        assertFavorabilityBinding(Map.of("25", "朋友"), "{\"25\":\"朋友\"}");
    }

    @Test
    void characterUpdateCanClearFavorability() throws Exception {
        assertFavorabilityBinding(null, null);
    }

    private void assertFavorabilityBinding(Map<String, String> value, String json) throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(CharacterTemplateMapper.class);
        var worldService = mock(IWorldTemplateService.class);
        when(worldService.getById(20L)).thenReturn(new WorldTemplate().setAuthorId(1L));
        var mapper = mock(CharacterTemplateMapper.class);
        var service = spy(new CharacterTemplateServiceImpl(worldService));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        doReturn(new CharacterTemplate().setId(9L)).when(service)
                .getCharacterTemplateByWorldId(20L, 9L);
        var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        service.updateCharacterTemplate(1L, 20L, 9L,
                new CharacterTemplate().setName("角色").setFavorability(value));
        verify(mapper).update(isNull(), captor.capture());
        var parameters = new HashMap<String, Object>();
        parameters.put("et", null);
        parameters.put("ew", captor.getValue());
        var statement = configuration.getMappedStatement(CharacterTemplateMapper.class.getName() + ".update");
        var boundSql = statement.getBoundSql(parameters);
        // Locate the favorability placeholder in the generated UPDATE, not by wrapper parameter names.
        String prefix = boundSql.getSql().split("favorability\\s*=")[0];
        int index = (int) prefix.chars().filter(c -> c == '?').count() + 1;
        var ps = mock(PreparedStatement.class);
        configuration.newParameterHandler(statement, parameters, boundSql).setParameters(ps);
        if (json == null) {
            verify(ps).setNull(index, Types.OTHER);
        } else {
            verify(ps).setObject(index, json, Types.OTHER);
        }
    }

    @Test
    void lockedWeaponQueryDeserializesRiskTags() throws Exception {
        var mapping = jsonMapping(CocCharacterWeaponMapper.class,
                "selectByCharacterIdAndNameForUpdate", "riskTags");
        var rs = mock(ResultSet.class);
        when(rs.getString("risk_tags")).thenReturn("[\"易走火\",\"噪音大\"]");
        assertThat(mapping.getTypeHandler().getResult(rs, "risk_tags"))
                .isEqualTo(List.of("易走火", "噪音大"));
    }

    @Test
    void lockedStashQueryDeserializesWeaponSnapshot() throws Exception {
        var mapping = jsonMapping(TrpgWeaponStashMapper.class,
                "selectByRunIdAndWeaponIdForUpdate", "weaponSnapshot");
        var rs = mock(ResultSet.class);
        when(rs.getString("weapon_snapshot")).thenReturn(
                "{\"name\":\"手枪\",\"remainingAmmo\":3,\"riskTags\":[\"噪音大\"]}");
        var result = (TrpgWeaponStash.WeaponSnapshot) mapping.getTypeHandler()
                .getResult(rs, "weapon_snapshot");
        assertThat(result.getName()).isEqualTo("手枪");
        assertThat(result.getRemainingAmmo()).isEqualTo(3);
        assertThat(result.getRiskTags()).containsExactly("噪音大");
    }

    private ResultMapping jsonMapping(Class<?> mapper, String method, String property) {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(mapper);
        var mappings = configuration.getMappedStatement(mapper.getName() + "." + method)
                .getResultMaps().getFirst().getResultMappings();
        assertThat(mappings).as("JSONB result mapping for %s", property)
                .anyMatch(mapping -> property.equals(mapping.getProperty()));
        return mappings.stream().filter(mapping -> property.equals(mapping.getProperty()))
                .findFirst().orElseThrow();
    }
}
