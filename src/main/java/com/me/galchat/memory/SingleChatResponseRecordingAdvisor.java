package com.me.galchat.memory;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/** Collects each model call before the tool advisor filters chunks or drops provider fields. */
public class SingleChatResponseRecordingAdvisor implements BaseAdvisor {

    static final String CONTEXT_KEY = "singleChatResponseRecording";
    private final int order;

    public SingleChatResponseRecordingAdvisor(int order) {
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        Object recorded = response.context().get(CONTEXT_KEY);
        if (recorded instanceof ChatClientMessageAggregator.AggregationState turn) {
            turn.append(response);
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // Keep the original stream so the outer memory advisor normalizes reasoning for the UI once.
        return new ChatClientMessageAggregator().aggregateChatClientResponse(
                chain.nextStream(request), response -> after(response, chain), false);
    }

    @Override
    public int getOrder() {
        return order;
    }
}
