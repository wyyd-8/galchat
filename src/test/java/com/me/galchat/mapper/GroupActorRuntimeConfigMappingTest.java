package com.me.galchat.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.me.galchat.domain.po.GroupActorRuntimeConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroupActorRuntimeConfigMappingTest {

    @Test
    void switchingToDefaultModelWritesNullInsteadOfKeepingPreviousBinding() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(GroupActorRuntimeConfigMapper.class);
        var saved = new GroupActorRuntimeConfig()
                .setId(14L).setConversationId(3L).setActorKey("kp")
                .setControlMode("MODEL").setModelApiId(null);
        var statement = configuration.getMappedStatement(
                GroupActorRuntimeConfigMapper.class.getName() + ".updateById");
        var sql = statement.getBoundSql(Map.of("et", saved));

        assertThat(sql.getSql()).contains("model_api_id=?");
        assertThat(sql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .contains("et.modelApiId");
    }
}
