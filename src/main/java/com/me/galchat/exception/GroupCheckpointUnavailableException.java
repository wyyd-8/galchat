package com.me.galchat.exception;

public class GroupCheckpointUnavailableException extends UserRequestException {
    public static final String CODE = "GROUP_CHECKPOINT_UNAVAILABLE";

    public GroupCheckpointUnavailableException() {
        super("未找到匹配的可恢复检查点，无法重试。请使用「撤回本轮对话」后重新发送。");
    }
}
