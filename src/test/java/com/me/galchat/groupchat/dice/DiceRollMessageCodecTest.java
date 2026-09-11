package com.me.galchat.groupchat.dice;

import com.me.galchat.exception.UserRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiceRollMessageCodecTest {

    private final DiceRollMessageCodec codec =
            new DiceRollMessageCodec(JsonMapper.builder().build());

    @Test
    void encodesDistinctRoundsInAscendingOrder() {
        assertThat(codec.encode(501L, List.of(3, 2, 3)))
                .isEqualTo("{\"summaryId\":501,\"roundNos\":[2,3]}");
    }

    @Test
    void decodesAValidReference() {
        assertThat(codec.decode("{\"summaryId\":501,\"roundNos\":[2,3]}"))
                .isEqualTo(new DiceRollMessageContent(501L, List.of(2, 3)));
    }

    @Test
    void rejectsMissingSummaryAndEmptyRounds() {
        assertThatThrownBy(
                () -> codec.decode("{\"summaryId\":null,\"roundNos\":[]}"))
                .isInstanceOf(UserRequestException.class);
    }
}
