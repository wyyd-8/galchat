package com.me.galchat.constant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsanityCatalogTest {

    @Test
    void catalogContainsEveryFixedEntryAndResolvesStableCodes() {
        assertThat(InsanityCatalog.summarySymptoms()).hasSize(10);
        assertThat(InsanityCatalog.phobias()).hasSize(100);
        assertThat(InsanityCatalog.manias()).hasSize(100);
        assertThat(InsanityCatalog.code(9, 37)).isEqualTo("9:037");
        assertThat(InsanityCatalog.display("9:037"))
                .isEqualTo("恐惧症（昆虫恐惧症：害怕昆虫）");
        assertThat(InsanityCatalog.display("10:036"))
                .isEqualTo("躁狂症（嗜酒狂：反常地渴望饮酒）");
    }

    @ParameterizedTest
    @ValueSource(strings = {"9", "9:000", "10:101", "11", "bad"})
    void catalogRejectsInvalidStoredCodes(String code) {
        assertThatThrownBy(() -> InsanityCatalog.display(code))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
