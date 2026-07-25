package com.me.galchat.groupchat.runtime;

import com.me.galchat.exception.UserRequestException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GroupRuntimeRegistry {

    private final List<GroupModeRuntime> runtimes;

    public GroupRuntimeRegistry(List<GroupModeRuntime> runtimes) {
        this.runtimes = List.copyOf(runtimes);
    }

    public GroupModeRuntime require(String mode) {
        return runtimes.stream()
                .filter(runtime -> runtime.mode().equals(mode))
                .findFirst()
                .orElseThrow(() -> new UserRequestException("未配置群聊运行时: " + mode));
    }
}
