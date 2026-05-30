package com.me.galchat.domain.dto;

import lombok.Data;

@Data
public class UserPasswordDTO {
    private String email;
    private String newPassword;
    private String verificationCode;
}
