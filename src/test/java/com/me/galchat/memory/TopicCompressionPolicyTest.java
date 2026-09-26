package com.me.galchat.memory;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TopicCompressionPolicyTest {
    @Test void highSemanticScoreSplitsEvenShortTopics() {
        assertThat(TopicSplitDecision.evaluate(400, 4000, 90).split()).isFalse();
        assertThat(TopicSplitDecision.evaluate(400, 4000, 91).split()).isTrue();
        assertThat(TopicSplitDecision.evaluate(763, 4000, 95).split()).isTrue();
        assertThat(TopicSplitDecision.evaluate(1395, 8000, 95).split()).isTrue();
        assertThat(TopicSplitDecision.evaluate(4000, 4000, 95).reason()).isEqualTo("capacity");
    }
    @Test void combinesScoreWithCurrentLengthAndKeepsContinuationUntilCapacity() {
        assertThat(TopicSplitDecision.evaluate(400, 4000, 90).split()).isFalse();
        assertThat(TopicSplitDecision.evaluate(3200, 4000, 90).split()).isTrue();
        assertThat(TopicSplitDecision.evaluate(3999, 4000, 10).split()).isFalse();
        assertThat(TopicSplitDecision.evaluate(4000, 4000, null).reason()).isEqualTo("capacity");
        assertThat(TopicSplitDecision.evaluate(7999, 8000, null).split()).isFalse();
        assertThat(TopicSplitDecision.evaluate(8000, 8000, 0).split()).isTrue();
    }
    @Test void rejectsBinaryMalformedAndOutOfRangeScores() {
        for (String text : List.of("true", "false", "{\"score\":101}", "{\"score\":-1}", "{\"score\":null}")) {
            assertThatThrownBy(() -> TopicSplitDecision.parseScore(text)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(TopicSplitDecision.parseScore("{\"score\":73}")).isEqualTo(73);
    }
    @Test void preservesSourceSpeakersOrderAndUnresolvedReferences() {
        var messages = List.of(new TopicSummaryCodec.Item("373", "user", "", "（摸摸脑袋）他可能明天来。"),
                new TopicSummaryCodec.Item("374", "assistant", "", "不一定会来。"));
        String result = TopicSummaryCodec.render(messages, """
                {"messages":[{"messageId":"373","compressedContent":"（摸摸脑袋）他可能明天来。"},
                {"messageId":"374","compressedContent":"不一定会来。"}]}
                """);
        assertThat(result).contains("user: （摸摸脑袋）他可能明天来。", "assistant: 不一定会来。");
        assertThat(result.indexOf("user:")).isLessThan(result.indexOf("assistant:"));
    }
    @Test void rejectsMissingDuplicateAndReorderedMessageIds() {
        var messages = List.of(new TopicSummaryCodec.Item("1", "user", "", "问"),
                new TopicSummaryCodec.Item("2", "assistant", "", "答"));
        for (String text : List.of("", "摘要", "{\"messages\":[]}",
                "{\"messages\":[{\"messageId\":\"2\",\"compressedContent\":\"答\"},{\"messageId\":\"1\",\"compressedContent\":\"问\"}]}",
                "{\"messages\":[{\"messageId\":\"1\",\"compressedContent\":\"问\"},{\"messageId\":\"1\",\"compressedContent\":\"答\"}]}")) {
            assertThatThrownBy(() -> TopicSummaryCodec.render(messages, text)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
