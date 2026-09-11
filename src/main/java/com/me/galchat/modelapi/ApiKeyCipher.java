package com.me.galchat.modelapi;

public interface ApiKeyCipher {
    String encrypt(String plaintext);
    String decrypt(String ciphertext);
}
