package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiceRollResultVO {
    private String formula;
    private List<DiceRollModuleVO> modules;
    private Integer result;
}
