package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class DiceRollModuleVO {
    private String expression;
    private int diceCount;
    private int diceSides;
    private String modifier;
    private List<DiceRollValueVO> dice;
    private int result;
}
