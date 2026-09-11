package com.me.galchat.exception;

import com.me.galchat.domain.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterCardCreationExceptionTest {

    @Test
    void returnsAStableErrorCodeAndCurrentDraftCursor() {
        CharacterCardCreationException exception = new CharacterCardCreationException(
                "DRAFT_VERSION_CONFLICT", "人物卡草稿版本已变化，请刷新后重试",
                4, "SKILLS", "CONFIRM_SKILLS");

        Result result = new GlobalExceptionHandler()
                .handleCharacterCardCreationException(exception);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isEqualTo(Map.of(
                "errorCode", "DRAFT_VERSION_CONFLICT",
                "version", 4,
                "currentStep", "SKILLS",
                "nextAction", "CONFIRM_SKILLS"));
    }
}
