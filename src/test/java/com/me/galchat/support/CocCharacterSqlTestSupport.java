package com.me.galchat.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.mapper.CocCharacterMapper;
import org.mockito.stubbing.Answer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

public final class CocCharacterSqlTestSupport {
    private CocCharacterSqlTestSupport() {
    }

    /** Check the actual SQL at the database boundary, not just the mutated entity. */
    public static AtomicInteger assertUpdatesClearRestraint(CocCharacterMapper mapper) {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(CocCharacterMapper.class);
        var writes = new AtomicInteger();
        Answer<Integer> checkSql = invocation -> {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("et", invocation.getArgument(0));
            if (invocation.getArguments().length == 2) {
                parameters.put("ew", invocation.getArgument(1));
            }
            var statement = configuration.getMappedStatement(
                    CocCharacterMapper.class.getName() + "." + invocation.getMethod().getName());
            var boundSql = statement.getBoundSql(parameters);
            String sql = boundSql.getSql().replaceAll("\\s+", " ");
            assertThat(sql).containsPattern("restrained_by_character_id\\s*=\\s*\\?");
            int parameterIndex = sql.substring(0, sql.indexOf("restrained_by_character_id"))
                    .replaceAll("[^?]", "").length();
            String property = boundSql.getParameterMappings().get(parameterIndex).getProperty();
            assertThat(configuration.newMetaObject(parameters).getValue(property)).isNull();
            assertThat(sql).containsPattern("WHERE.*id\\s*=\\s*\\?");
            String idProperty = boundSql.getParameterMappings().getLast().getProperty();
            assertThat(configuration.newMetaObject(parameters).getValue(idProperty))
                    .isEqualTo(((CocCharacter) parameters.get("et")).getId());
            writes.incrementAndGet();
            return 1;
        };
        doAnswer(checkSql).when(mapper).updateById(any(CocCharacter.class));
        doAnswer(checkSql).when(mapper).update(any(CocCharacter.class), any());
        return writes;
    }
}
