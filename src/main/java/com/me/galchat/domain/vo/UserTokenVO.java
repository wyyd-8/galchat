package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserTokenVO {
    private String token;
    private Long id;
    private String username;
}
