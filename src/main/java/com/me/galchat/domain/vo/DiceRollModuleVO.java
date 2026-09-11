package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiceRollModuleVO {
    private String expression;
    private int diceCount;
    private int diceSides;
    private String modifier;
    private List<DiceRollValueVO> dice;
    private Integer result;
}
