package com.me.galchat.modelapi;

public class ModelApiTransportException extends RuntimeException {

    private final String code;

    public ModelApiTransportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
