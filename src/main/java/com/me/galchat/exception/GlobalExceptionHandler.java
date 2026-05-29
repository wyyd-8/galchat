package com.me.galchat.exception;

import com.me.galchat.domain.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler
    public Result handleUserRequestException(UserRequestException e) {
        log.warn("用户请求异常：{}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler
    public Result handleUserAuthException(UserAuthException e) {
        log.warn("用户认证异常：{}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler
    public Result handleUserNotFoundException(UserNotFoundException e) {
        log.warn("用户不存在：{}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler
    public Result handleConversationIdException(ConversationIdException e) {
        log.warn("会话id异常：{}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler
    public Result handleException(Exception e) {//异常会按照继承关系从小往大匹配
        log.error("出现异常！类型：{}", e.getClass().getName(), e);
        return Result.error("出现异常！" + e.getMessage());
    }

    @ExceptionHandler
    public Result handleDuplicateKeyException(DuplicateKeyException e) {//重复的unique值
        log.error("出现异常！", e);
        String s = e.getMessage();
        if (s != null && s.contains("user_info") && s.contains("email")) {
            return Result.error("邮箱已被占用");
        }
        if (s != null && s.contains("Duplicate entry")) {
            s = s.substring(s.indexOf("Duplicate entry"));
            return Result.error("值已被占用 : " + s.split(" ")[2]);
        }
        return Result.error("值已被占用");
    }
}
