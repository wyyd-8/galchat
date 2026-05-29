package com.me.galchat.service;

public interface InputCompletionClassifier {

    boolean isComplete(String text, String context);
}
