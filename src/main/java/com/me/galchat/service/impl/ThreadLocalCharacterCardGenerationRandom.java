package com.me.galchat.service.impl;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class ThreadLocalCharacterCardGenerationRandom
        implements CharacterCardGenerationRandom {
    @Override
    public int roll(int sides) {
        return ThreadLocalRandom.current().nextInt(1, sides + 1);
    }
}
