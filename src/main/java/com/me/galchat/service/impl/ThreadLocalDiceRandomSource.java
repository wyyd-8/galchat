package com.me.galchat.service.impl;

import com.me.galchat.service.DiceRandomSource;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class ThreadLocalDiceRandomSource implements DiceRandomSource {

    @Override
    public int d100() {
        return ThreadLocalRandom.current().nextInt(1, 101);
    }
}
