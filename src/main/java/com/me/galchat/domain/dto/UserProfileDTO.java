package com.me.galchat.domain.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;

@Data
public class UserProfileDTO {
    private String username;
    private String email;
    private LocalDate birthday;
    private String diceSkin;

    @JsonIgnore
    private boolean birthdayProvided;

    public void setBirthday(LocalDate birthday) {
        this.birthday = birthday;
        this.birthdayProvided = true;
    }
}
