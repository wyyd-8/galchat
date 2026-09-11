package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiceRollValueVO {
    private int sides;
    private Integer value;
    private String role;
    private boolean selected;
}
