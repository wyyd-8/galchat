package com.me.galchat.domain.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UserProfileDTO {
    private String username;
    private String email;
    private LocalDate birthday;
}
