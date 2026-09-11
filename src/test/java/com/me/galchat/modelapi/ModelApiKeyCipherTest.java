package com.me.galchat.modelapi;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelApiKeyCipherTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void encryptsWithRandomNonceAndDecryptsBothCiphertexts() {
        ModelApiKeyCipher cipher = new ModelApiKeyCipher(MASTER_KEY);

        String first = cipher.encrypt("sk-sensitive-value");
        String second = cipher.encrypt("sk-sensitive-value");

        assertThat(first).startsWith("v1:")
                .doesNotContain("sk-sensitive-value")
                .isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("sk-sensitive-value");
        assertThat(cipher.decrypt(second)).isEqualTo("sk-sensitive-value");
    }

    @Test
    void refusesMissingOrWrongSizedMasterKey() {
        assertThatThrownBy(() -> new ModelApiKeyCipher(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ModelApiKeyCipher(
                Base64.getEncoder().encodeToString("too-short".getBytes())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesTamperedCiphertext() {
        ModelApiKeyCipher cipher = new ModelApiKeyCipher(MASTER_KEY);
        String encrypted = cipher.encrypt("sk-sensitive-value");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }
}
