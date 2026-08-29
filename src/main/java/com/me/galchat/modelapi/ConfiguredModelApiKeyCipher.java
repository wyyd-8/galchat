package com.me.galchat.modelapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredModelApiKeyCipher implements ApiKeyCipher {

    private final String encodedMasterKey;
    private volatile ModelApiKeyCipher delegate;

    public ConfiguredModelApiKeyCipher(
            @Value("${galchat.model-api.master-key:${GALCHAT_MODEL_API_MASTER_KEY:}}")
            String encodedMasterKey) {
        this.encodedMasterKey = encodedMasterKey;
    }

    @Override
    public String encrypt(String plaintext) {
        return delegate().encrypt(plaintext);
    }

    @Override
    public String decrypt(String ciphertext) {
        return delegate().decrypt(ciphertext);
    }

    private ModelApiKeyCipher delegate() {
        ModelApiKeyCipher current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (delegate == null) {
                delegate = new ModelApiKeyCipher(encodedMasterKey);
            }
            return delegate;
        }
    }
}
