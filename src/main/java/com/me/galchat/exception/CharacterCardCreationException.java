package com.me.galchat.exception;

public class CharacterCardCreationException extends UserRequestException {

    private final String errorCode;
    private final Integer version;
    private final String currentStep;
    private final String nextAction;

    public CharacterCardCreationException(
            String errorCode,
            String message,
            Integer version,
            String currentStep,
            String nextAction) {
        super(message);
        this.errorCode = errorCode;
        this.version = version;
        this.currentStep = currentStep;
        this.nextAction = nextAction;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Integer getVersion() {
        return version;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public String getNextAction() {
        return nextAction;
    }
}
