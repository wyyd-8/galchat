package com.me.galchat.exception;

public class UserRequestException extends RuntimeException {
    public UserRequestException(String message) {
        super(message);
    }
}
