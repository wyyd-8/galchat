package com.me.galchat.service;

@FunctionalInterface
public interface ChatUserMessageListener {

    void onUserMessageSaved(Long userMessageId);
}
