package com.me.galchat.service.impl.dice;

import com.me.galchat.constant.CocCheckOutcome;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SanLossExpressionTest {
    @ParameterizedTest
    @CsvSource({"1,false,1,1", "1d6,false,1,6", "1/1d3,true,1,3", "1d3/1d6,true,1,6", "2d3+1/2d6+2,true,3,14", "1d6/1D6,false,1,6"})
    void parsesLossBranchesAndTheirExactBounds(String text,boolean check,int minimum,int maximum) {
        var loss=SanLossExpression.parse(text);
        assertThat(loss.needsCheck()).isEqualTo(check);
        assertThat(CocDiceRules.selectSanLossFormula(CocCheckOutcome.CRITICAL_SUCCESS,loss.success(),loss.failure())).isEqualTo(Integer.toString(minimum));
        assertThat(CocDiceRules.selectSanLossFormula(CocCheckOutcome.FUMBLE,loss.success(),loss.failure())).isEqualTo(Integer.toString(maximum));
    }
    @ParameterizedTest @ValueSource(strings={"", "/1", "1/", "1/2/3", "-1", "1D0", "0D6", "99999999999999999", "1D1000001"})
    void rejectsMalformedOrOutOfRangeExpressions(String text) {
        assertThatThrownBy(()->SanLossExpression.parse(text)).isInstanceOf(com.me.galchat.exception.UserRequestException.class);
    }
}
