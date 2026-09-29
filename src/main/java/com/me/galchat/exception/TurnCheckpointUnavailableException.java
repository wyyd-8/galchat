package com.me.galchat.exception;

public class TurnCheckpointUnavailableException extends UserRequestException {
    public static final String CODE = "TURN_CHECKPOINT_UNAVAILABLE";

    public TurnCheckpointUnavailableException() {
        super("未找到匹配的可恢复检查点，无法重试。请打开「跑团工具 → 存档」，使用「回退至上一轮」恢复后继续。");
    }
}
