package com.me.galchat.service.impl.trpg;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class TrpgSelectionRandomizer {

    public <T> T choose(List<T> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("随机候选项不能为空");
        }
        return values.get(ThreadLocalRandom.current()
                .nextInt(values.size()));
    }
}
