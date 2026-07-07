package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DiceRollValueVO {
    private int sides;
    private int value;
    private String role;
    private boolean selected;
}
