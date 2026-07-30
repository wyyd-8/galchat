package com.me.galchat.groupchat.runtime.trpg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionActionStreamParserTest {

    @Test
    void routesDecisionAndActionWhenEveryCharacterIsItsOwnChunk() {
        DecisionActionStreamParser parser =
                new DecisionActionStreamParser();
        String source = """
                <decision>先确认门后的声响，再让同伴保持距离。</decision>
                <action>我贴近门边，侧耳听里面的动静。</action>
                """;
        StringBuilder decision = new StringBuilder();
        StringBuilder action = new StringBuilder();
        int completedEvents = 0;

        for (int index = 0; index < source.length(); index++) {
            DecisionActionStreamParser.Delta delta =
                    parser.accept(source.substring(index, index + 1));
            decision.append(delta.decision());
            action.append(delta.action());
            if (delta.decisionCompleted()) {
                completedEvents++;
            }
        }
        parser.finish();

        assertThat(decision.toString())
                .isEqualTo("先确认门后的声响，再让同伴保持距离。");
        assertThat(action.toString())
                .isEqualTo("我贴近门边，侧耳听里面的动静。");
        assertThat(completedEvents).isEqualTo(1);
        assertThat(parser.decision())
                .isEqualTo("先确认门后的声响，再让同伴保持距离。");
        assertThat(parser.action())
                .isEqualTo("我贴近门边，侧耳听里面的动静。");
    }

    @Test
    void routesBothPartsFromOneChunkAndAllowsOuterWhitespace() {
        DecisionActionStreamParser parser =
                new DecisionActionStreamParser();

        DecisionActionStreamParser.Delta delta = parser.accept(
                " \n<decision>观察窗户。</decision>"
                        + "<action>我检查窗框。</action>\n ");
        parser.finish();

        assertThat(delta.decision()).isEqualTo("观察窗户。");
        assertThat(delta.action()).isEqualTo("我检查窗框。");
        assertThat(delta.decisionCompleted()).isTrue();
    }

    @Test
    void rejectsMalformedOrIncompleteProtocols() {
        List<String> malformed = List.of(
                "正文<decision>判断</decision><action>行动</action>",
                "<action>行动</action><decision>判断</decision>",
                "<decision></decision><action>行动</action>",
                "<decision>判断</decision><action> </action>",
                "<decision>判断</decision>",
                "<decision>判断<decision>重复</decision></decision><action>行动</action>",
                "<decision>判断</decision><action>行动</action>尾文");
        List<Throwable> failures = new ArrayList<>();

        for (String source : malformed) {
            DecisionActionStreamParser parser =
                    new DecisionActionStreamParser();
            try {
                parser.accept(source);
                parser.finish();
            } catch (Throwable error) {
                failures.add(error);
            }
        }

        assertThat(failures).hasSize(malformed.size())
                .allSatisfy(error -> assertThat(error)
                        .isInstanceOf(
                                DecisionActionProtocolException.class));
    }

    @Test
    void rejectsAdditionalContentAfterCompletionInLaterChunk() {
        DecisionActionStreamParser parser =
                new DecisionActionStreamParser();
        parser.accept(
                "<decision>判断</decision><action>行动</action>");

        assertThatThrownBy(() -> parser.accept("不是空白"))
                .isInstanceOf(DecisionActionProtocolException.class);
    }
}
