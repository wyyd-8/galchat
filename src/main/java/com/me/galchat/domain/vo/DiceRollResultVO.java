package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class DiceRollResultVO {
    private String formula;
    private List<DiceRollModuleVO> modules;
    private int result;
}
