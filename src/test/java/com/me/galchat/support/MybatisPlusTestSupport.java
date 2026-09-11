package com.me.galchat.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

public final class MybatisPlusTestSupport {

    private MybatisPlusTestSupport() {
    }

    public static void initialize(Class<?>... entityTypes) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "");
        for (Class<?> entityType : entityTypes) {
            TableInfoHelper.initTableInfo(assistant, entityType);
        }
    }
}
