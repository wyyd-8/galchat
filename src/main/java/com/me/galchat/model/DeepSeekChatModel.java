/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.me.galchat.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletion;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletion.Choice;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.ChatCompletionFunction;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.ToolCall;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionRequest;
import org.springframework.ai.deepseek.api.common.DeepSeekConstants;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.support.UsageCalculator;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/**
 * {@link ChatModel} and {@link StreamingChatModel} implementation for {@literal DeepSeek}
 * backed by {@link DeepSeekApi}.
 *
 * @author Geng Rong
 */
public class DeepSeekChatModel implements ChatModel {

	private static final Logger logger = LoggerFactory.getLogger(DeepSeekChatModel.class);

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

	private static final ToolCallingManager DEFAULT_TOOL_CALLING_MANAGER = ToolCallingManager.builder().build();

	private static final String RAW_TOOL_CALLS_METADATA_KEY = "deepSeekRawToolCalls";

	/**
	 * The default options used for the chat completion requests.
	 */
	private final DeepSeekChatOptions defaultOptions;

	/**
	 * The retry template used to retry the DeepSeek API calls.
	 */
	public final RetryTemplate retryTemplate;

	/**
	 * Low-level access to the DeepSeek API.
	 */
	private final DeepSeekApi deepSeekApi;

	/**
	 * Observation registry used for instrumentation.
	 */
	private final ObservationRegistry observationRegistry;

	/**
	 * The tool calling manager used to execute tools.
	 */
	private final ToolCallingManager toolCallingManager;

	/**
	 * The tool execution eligibility predicate used to determine if a tool can be
	 * executed.
	 */
	private final ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate;

	/**
	 * Conventions to use for generating observations.
	 */
	private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	public DeepSeekChatModel(DeepSeekApi deepSeekApi, DeepSeekChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
			ObservationRegistry observationRegistry) {
		this(deepSeekApi, defaultOptions, toolCallingManager, retryTemplate, observationRegistry,
				new DefaultToolExecutionEligibilityPredicate());
	}

	public DeepSeekChatModel(DeepSeekApi deepSeekApi, DeepSeekChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, RetryTemplate retryTemplate, ObservationRegistry observationRegistry,
			ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate) {
		Assert.notNull(deepSeekApi, "deepSeekApi cannot be null");
		Assert.notNull(defaultOptions, "defaultOptions cannot be null");
		Assert.notNull(toolCallingManager, "toolCallingManager cannot be null");
		Assert.notNull(retryTemplate, "retryTemplate cannot be null");
		Assert.notNull(observationRegistry, "observationRegistry cannot be null");
		Assert.notNull(toolExecutionEligibilityPredicate, "toolExecutionEligibilityPredicate cannot be null");
		this.deepSeekApi = deepSeekApi;
		this.defaultOptions = defaultOptions;
		this.toolCallingManager = toolCallingManager;
		this.retryTemplate = retryTemplate;
		this.observationRegistry = observationRegistry;
		this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		Prompt requestPrompt = buildRequestPrompt(prompt);
		return this.internalCall(requestPrompt, null);
	}

	private ChatResponse internalCall(Prompt prompt, @Nullable ChatResponse previousChatResponse) {

		ChatCompletionRequest request = createRequest(prompt, false);

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(prompt)
			.provider(DeepSeekConstants.PROVIDER_NAME)
			.build();

		ChatResponse response = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {

				ResponseEntity<ChatCompletion> completionEntity = RetryUtils.execute(this.retryTemplate,
						() -> this.deepSeekApi.chatCompletionEntity(request));

				var chatCompletion = completionEntity.getBody();

				if (chatCompletion == null) {
					logger.warn("No chat completion returned for prompt: {}", prompt);
					return new ChatResponse(List.of());
				}

				List<Choice> choices = chatCompletion.choices();
				if (choices == null) {
					logger.warn("No choices returned for prompt: {}", prompt);
					return new ChatResponse(List.of());
				}

				List<Generation> generations = choices.stream().map(choice -> {
			// @formatter:off
					Map<String, Object> metadata = Map.of(
							"id", chatCompletion.id() != null ? chatCompletion.id() : "",
							"role", choice.message().role() != null ? choice.message().role().name() : "",
							"index", choice.index(),
							"finishReason", choice.finishReason() != null ? choice.finishReason().name() : "");
					// @formatter:on
					return buildGeneration(choice, metadata);
				}).toList();

				// Current usage
				ChatCompletion body = completionEntity.getBody();
				Assert.state(body != null, "Body must not be null");
				DeepSeekApi.Usage usage = body.usage();
				Usage currentChatResponseUsage = usage != null ? getDefaultUsage(usage) : new EmptyUsage();
				Usage accumulatedUsage = UsageCalculator.getCumulativeUsage(currentChatResponseUsage,
						previousChatResponse);
				ChatResponse chatResponse = new ChatResponse(generations, from(body, accumulatedUsage));

				observationContext.setResponse(chatResponse);

				return chatResponse;

			});
		ChatOptions options = prompt.getOptions();
		Assert.state(options != null, "options must not be null");
		if (this.toolExecutionEligibilityPredicate.isToolExecutionRequired(options, response)) {
			var toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);
			if (toolExecutionResult.returnDirect()) {
				// Return tool execution result directly to the client.
				return ChatResponse.builder()
					.from(response)
					.generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
					.build();
			}
			else {
				// Send the tool execution result back to the model.
				return this.internalCall(new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()),
						response);
			}
		}

		return response;
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		Prompt requestPrompt = buildRequestPrompt(prompt);
		return internalStream(requestPrompt, null);
	}

	private Flux<ChatResponse> internalStream(Prompt prompt, @Nullable ChatResponse previousChatResponse) {
		return Flux.deferContextual(contextView -> {
			ChatCompletionRequest request = createRequest(prompt, true);

			Flux<DeepSeekApi.ChatCompletionChunk> completionChunks = this.deepSeekApi.chatCompletionStream(request);

			// For chunked responses, only the first chunk contains the choice role.
			// The rest of the chunks with same ID share the same role.
			ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();

			final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt)
				.provider(DeepSeekConstants.PROVIDER_NAME)
				.build();

			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
					this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry);

			observation.parentObservation(contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null)).start();

			Flux<ChatResponse> chatResponse = completionChunks.map(this::chunkToChatCompletion)
				.switchMap(chatCompletion -> Mono.just(chatCompletion).map(chatCompletion2 -> {
					try {
						String id = chatCompletion2.id();

						List<Generation> generations = chatCompletion2.choices().stream().map(choice -> {
							if (choice.message().role() != null) {
								roleMap.putIfAbsent(id, choice.message().role().name());
							}

				// @formatter:off
								Map<String, Object> metadata = Map.of(
										"id", chatCompletion2.id(),
										"role", roleMap.getOrDefault(id, ""),
										"finishReason", choice.finishReason() != null ? choice.finishReason().name() : ""
								);
  				// @formatter:on
							return buildGeneration(choice, metadata);
						}).toList();
						DeepSeekApi.Usage usage = chatCompletion2.usage();
						Usage currentUsage = (usage != null) ? getDefaultUsage(usage) : new EmptyUsage();
						Usage cumulativeUsage = UsageCalculator.getCumulativeUsage(currentUsage, previousChatResponse);

						return new ChatResponse(generations, from(chatCompletion2, cumulativeUsage));
					}
					catch (Exception e) {
						logger.error("Error processing chat completion", e);
						return new ChatResponse(List.of());
					}

				}));

			// @formatter:off
			Flux<ChatResponse> flux = executeToolCallsAfterStream(prompt, chatResponse)
			.doOnError(observation::error)
			.doFinally(s -> observation.stop())
			.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
			// @formatter:on

			return new MessageAggregator().aggregate(flux, observationContext::setResponse);

		});
	}

	private Flux<ChatResponse> executeToolCallsAfterStream(Prompt prompt, Flux<ChatResponse> chatResponse) {
		StreamAggregationState aggregationState = new StreamAggregationState();
		Flux<ChatResponse> visibleResponses = chatResponse
				.doOnSubscribe(subscription -> aggregationState.reset())
				.doOnNext(aggregationState::append)
				.filter(response -> !response.hasToolCalls());

		return visibleResponses.concatWith(Flux.deferContextual(ctx -> {
			ChatResponse aggregatedResponse = aggregationState.toChatResponse();
			ChatOptions options = prompt.getOptions();
			Assert.state(options != null, "options must not be null");
			if (!this.toolExecutionEligibilityPredicate.isToolExecutionRequired(options, aggregatedResponse)) {
				return Flux.empty();
			}

			return Flux.deferContextual(toolContext -> {
				ToolExecutionResult toolExecutionResult;
				try {
					ToolCallReactiveContextHolder.setContext(toolContext);
					toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, aggregatedResponse);
				}
				finally {
					ToolCallReactiveContextHolder.clearContext();
				}
				if (toolExecutionResult.returnDirect()) {
					return Flux.just(ChatResponse.builder()
							.from(aggregatedResponse)
							.generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
							.build());
				}
				return this.internalStream(new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()),
						aggregatedResponse);
			}).subscribeOn(Schedulers.boundedElastic());
		}));
	}

	private Generation buildGeneration(Choice choice, Map<String, Object> metadata) {
		List<ToolCall> rawToolCalls = choice.message().toolCalls();
		List<AssistantMessage.ToolCall> toolCalls = rawToolCalls == null ? List.of()
				: rawToolCalls.stream().map(this::toAssistantToolCall).toList();

		String finishReason = (choice.finishReason() != null ? choice.finishReason().name() : "");
		var generationMetadataBuilder = ChatGenerationMetadata.builder().finishReason(finishReason);

		String textContent = choice.message().content();
		String reasoningContent = choice.message().reasoningContent();

		Map<String, Object> properties = new HashMap<>(metadata);
		if (!CollectionUtils.isEmpty(rawToolCalls)) {
			properties.put(RAW_TOOL_CALLS_METADATA_KEY, rawToolCalls);
		}

		DeepSeekAssistantMessage.Builder builder = new DeepSeekAssistantMessage.Builder();
		DeepSeekAssistantMessage assistantMessage = builder.content(textContent)
			.reasoningContent(reasoningContent)
			.properties(properties)
			.toolCalls(toolCalls)
			.build();

		return new Generation(assistantMessage, generationMetadataBuilder.build());
	}

	private AssistantMessage.ToolCall toAssistantToolCall(ToolCall toolCall) {
		ChatCompletionFunction function = toolCall.function();
		return new AssistantMessage.ToolCall(toolCall.id(), toolCall.type() == null ? "function" : toolCall.type(),
				function == null ? null : function.name(), function == null ? null : function.arguments());
	}

	private static class StreamAggregationState {

		private final StringBuilder content = new StringBuilder();
		private final StringBuilder reasoningContent = new StringBuilder();
		private final Map<String, Object> properties = new HashMap<>();
		private final Map<Integer, ToolCallAccumulator> toolCallsByIndex = new LinkedHashMap<>();
		private final List<ToolCallAccumulator> unindexedToolCalls = new ArrayList<>();
		private ChatResponseMetadata responseMetadata;
		private ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.NULL;
		private boolean hasAssistantOutput;

		void reset() {
			content.setLength(0);
			reasoningContent.setLength(0);
			properties.clear();
			toolCallsByIndex.clear();
			unindexedToolCalls.clear();
			responseMetadata = null;
			generationMetadata = ChatGenerationMetadata.NULL;
			hasAssistantOutput = false;
		}

		void append(ChatResponse response) {
			if (response == null) {
				return;
			}
			if (response.getMetadata() != null) {
				responseMetadata = response.getMetadata();
			}
			for (Generation generation : response.getResults()) {
				append(generation);
			}
		}

		private void append(Generation generation) {
			if (generation == null || generation.getOutput() == null) {
				return;
			}
			if (generation.getMetadata() != null && generation.getMetadata() != ChatGenerationMetadata.NULL) {
				generationMetadata = generation.getMetadata();
			}

			AssistantMessage output = generation.getOutput();
			appendText(content, output.getText());
			if (output instanceof DeepSeekAssistantMessage deepSeekAssistantMessage) {
				appendText(reasoningContent, deepSeekAssistantMessage.getReasoningContent());
			}
			if (output.getMetadata() != null) {
				properties.putAll(output.getMetadata());
				appendRawToolCalls(output.getMetadata().get(RAW_TOOL_CALLS_METADATA_KEY));
			}
			if (CollectionUtils.isEmpty(toolCalls()) && !CollectionUtils.isEmpty(output.getToolCalls())) {
				appendAssistantToolCalls(output.getToolCalls());
			}
			hasAssistantOutput = true;
		}

		private void appendRawToolCalls(Object rawToolCalls) {
			if (!(rawToolCalls instanceof List<?> list)) {
				return;
			}
			for (Object item : list) {
				if (item instanceof ToolCall toolCall) {
					ToolCallAccumulator accumulator = accumulator(toolCall);
					accumulator.append(toolCall);
				}
			}
		}

		private ToolCallAccumulator accumulator(ToolCall toolCall) {
			Integer index = toolCall.index();
			if (index != null) {
				return toolCallsByIndex.computeIfAbsent(index, ignored -> new ToolCallAccumulator());
			}

			if (toolCall.id() != null) {
				for (ToolCallAccumulator accumulator : unindexedToolCalls) {
					if (toolCall.id().equals(accumulator.id)) {
						return accumulator;
					}
				}
			}

			ToolCallAccumulator accumulator = new ToolCallAccumulator();
			unindexedToolCalls.add(accumulator);
			return accumulator;
		}

		private void appendAssistantToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
			for (AssistantMessage.ToolCall toolCall : toolCalls) {
				ToolCallAccumulator accumulator = new ToolCallAccumulator();
				accumulator.id = toolCall.id();
				accumulator.type = toolCall.type();
				accumulator.name = toolCall.name();
				appendText(accumulator.arguments, toolCall.arguments());
				unindexedToolCalls.add(accumulator);
			}
		}

		ChatResponse toChatResponse() {
			if (!hasAssistantOutput) {
				return null;
			}

			AssistantMessage assistantMessage = assistantMessage();
			return new ChatResponse(List.of(new Generation(assistantMessage, generationMetadata)),
					responseMetadata == null ? ChatResponseMetadata.builder().build() : responseMetadata);
		}

		private AssistantMessage assistantMessage() {
			List<AssistantMessage.ToolCall> toolCalls = toolCalls();
			properties.remove(RAW_TOOL_CALLS_METADATA_KEY);
			if (!reasoningContent.isEmpty()) {
				return new DeepSeekAssistantMessage.Builder()
						.content(content.toString())
						.reasoningContent(reasoningContent.toString())
						.properties(properties)
						.toolCalls(toolCalls)
						.build();
			}
			return AssistantMessage.builder()
					.content(content.toString())
					.properties(properties)
					.toolCalls(toolCalls)
					.build();
		}

		private List<AssistantMessage.ToolCall> toolCalls() {
			List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
			toolCallsByIndex.values().stream()
					.map(ToolCallAccumulator::toToolCall)
					.forEach(toolCalls::add);
			unindexedToolCalls.stream()
					.map(ToolCallAccumulator::toToolCall)
					.forEach(toolCalls::add);
			return toolCalls;
		}

		private void appendText(StringBuilder builder, String text) {
			if (text != null) {
				builder.append(text);
			}
		}
	}

	private static class ToolCallAccumulator {

		private String id;
		private String type = "function";
		private String name;
		private final StringBuilder arguments = new StringBuilder();

		void append(ToolCall toolCall) {
			if (toolCall.id() != null) {
				id = toolCall.id();
			}
			if (toolCall.type() != null) {
				type = toolCall.type();
			}
			ChatCompletionFunction function = toolCall.function();
			if (function == null) {
				return;
			}
			if (function.name() != null) {
				name = function.name();
			}
			if (function.arguments() != null) {
				arguments.append(function.arguments());
			}
		}

		AssistantMessage.ToolCall toToolCall() {
			return new AssistantMessage.ToolCall(id, type, name, arguments.toString());
		}
	}

	private ChatResponseMetadata from(ChatCompletion result, Usage usage) {
		Assert.notNull(result, "DeepSeek ChatCompletionResult must not be null");
		var builder = ChatResponseMetadata.builder()
			.id(result.id() != null ? result.id() : "")
			.usage(usage)
			.model(result.model() != null ? result.model() : "")
			.keyValue("created", result.created() != null ? result.created() : 0L)
			.keyValue("system-fingerprint", result.systemFingerprint() != null ? result.systemFingerprint() : "");
		return builder.build();
	}

	private ChatResponseMetadata from(ChatResponseMetadata chatResponseMetadata, Usage usage) {
		Assert.notNull(chatResponseMetadata, "DeepSeek ChatResponseMetadata must not be null");
		var builder = ChatResponseMetadata.builder()
			.id(chatResponseMetadata.getId() != null ? chatResponseMetadata.getId() : "")
			.usage(usage)
			.model(chatResponseMetadata.getModel() != null ? chatResponseMetadata.getModel() : "");
		return builder.build();
	}

	/**
	 * Convert the ChatCompletionChunk into a ChatCompletion. The Usage is set to null.
	 * @param chunk the ChatCompletionChunk to convert
	 * @return the ChatCompletion
	 */
	private ChatCompletion chunkToChatCompletion(DeepSeekApi.ChatCompletionChunk chunk) {
		List<Choice> choices = chunk.choices()
			.stream()
			.map(chunkChoice -> new Choice(chunkChoice.finishReason(), chunkChoice.index(), chunkChoice.delta(),
					chunkChoice.logprobs()))
			.toList();

		return new ChatCompletion(chunk.id(), choices, chunk.created(), chunk.model(), chunk.serviceTier(),
				chunk.systemFingerprint(), chunk.usage());
	}

	private DefaultUsage getDefaultUsage(DeepSeekApi.Usage usage) {
		return new DefaultUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), usage);
	}

	Prompt buildRequestPrompt(Prompt prompt) {
		DeepSeekChatOptions runtimeOptions = null;
		if (prompt.getOptions() != null) {
			if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions) {
				runtimeOptions = ModelOptionsUtils.copyToTarget(toolCallingChatOptions, ToolCallingChatOptions.class,
						DeepSeekChatOptions.class);
			}
			else {
				runtimeOptions = ModelOptionsUtils.copyToTarget(prompt.getOptions(), ChatOptions.class,
						DeepSeekChatOptions.class);
			}
		}

		DeepSeekChatOptions requestOptions = ModelOptionsUtils.merge(runtimeOptions, this.defaultOptions,
				DeepSeekChatOptions.class);

		if (runtimeOptions != null) {
			requestOptions.setInternalToolExecutionEnabled(
					ModelOptionsUtils.mergeOption(runtimeOptions.getInternalToolExecutionEnabled(),
							this.defaultOptions.getInternalToolExecutionEnabled()));
			requestOptions.setToolNames(ToolCallingChatOptions.mergeToolNames(runtimeOptions.getToolNames(),
					this.defaultOptions.getToolNames()));
			requestOptions.setToolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(runtimeOptions.getToolCallbacks(),
					this.defaultOptions.getToolCallbacks()));
			requestOptions.setToolContext(ToolCallingChatOptions.mergeToolContext(runtimeOptions.getToolContext(),
					this.defaultOptions.getToolContext()));
		}
		else {
			requestOptions.setInternalToolExecutionEnabled(this.defaultOptions.getInternalToolExecutionEnabled());
			requestOptions.setToolNames(this.defaultOptions.getToolNames());
			requestOptions.setToolCallbacks(this.defaultOptions.getToolCallbacks());
			requestOptions.setToolContext(this.defaultOptions.getToolContext());
		}

		ToolCallingChatOptions.validateToolCallbacks(requestOptions.getToolCallbacks());

		return new Prompt(prompt.getInstructions(), requestOptions);
	}

	/**
	 * Accessible for testing.
	 */
	ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {
		List<ChatCompletionMessage> chatCompletionMessages = prompt.getInstructions().stream().map(message -> {
			if (message.getMessageType() == MessageType.USER || message.getMessageType() == MessageType.SYSTEM) {
				String text = message.getText();
				Assert.state(text != null, "text must not be null");
				return List.of(new ChatCompletionMessage(text,
						ChatCompletionMessage.Role.valueOf(message.getMessageType().name())));
			}
			else if (message.getMessageType() == MessageType.ASSISTANT) {
				var assistantMessage = (AssistantMessage) message;
				List<ToolCall> toolCalls = null;
				if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
					toolCalls = assistantMessage.getToolCalls().stream().map(toolCall -> {
						var function = new ChatCompletionFunction(toolCall.name(), toolCall.arguments());
						return new ToolCall(toolCall.id(), toolCall.type(), function);
					}).toList();
				}
				Boolean isPrefixAssistantMessage = null;
				if (message instanceof DeepSeekAssistantMessage
						&& Boolean.TRUE.equals(((DeepSeekAssistantMessage) message).getPrefix())) {
					isPrefixAssistantMessage = true;
				}
				String reasoningContent = null;
				if (message instanceof DeepSeekAssistantMessage deepSeekAssistantMessage) {
					reasoningContent = deepSeekAssistantMessage.getReasoningContent();
				}
				String text = assistantMessage.getText();
				if (text == null) {
					text = "";
				}
				return List.of(new ChatCompletionMessage(text, ChatCompletionMessage.Role.ASSISTANT, null, null,
						toolCalls, isPrefixAssistantMessage, reasoningContent));
			}
			else if (message.getMessageType() == MessageType.TOOL) {
				ToolResponseMessage toolMessage = (ToolResponseMessage) message;

				toolMessage.getResponses()
					.forEach(response -> Assert.isTrue(response.id() != null, "ToolResponseMessage must have an id"));
				return toolMessage.getResponses()
					.stream()
					.map(tr -> new ChatCompletionMessage(tr.responseData(), ChatCompletionMessage.Role.TOOL, tr.name(),
							tr.id(), null))
					.toList();
			}
			else {
				throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
			}
		}).flatMap(List::stream).toList();

		ChatCompletionRequest request = new ChatCompletionRequest(chatCompletionMessages, stream);

		DeepSeekChatOptions requestOptions = (DeepSeekChatOptions) prompt.getOptions();
		Assert.state(requestOptions != null, "requestOptions must not be null");
		request = ModelOptionsUtils.merge(requestOptions, request, ChatCompletionRequest.class);

		// Add the tool definitions to the request's tools parameter.
		List<ToolDefinition> toolDefinitions = this.toolCallingManager.resolveToolDefinitions(requestOptions);
		if (!CollectionUtils.isEmpty(toolDefinitions)) {
			request = ModelOptionsUtils.merge(
					DeepSeekChatOptions.builder().tools(this.getFunctionTools(toolDefinitions)).build(), request,
					ChatCompletionRequest.class);
		}

		return request;
	}

	private List<DeepSeekApi.FunctionTool> getFunctionTools(List<ToolDefinition> toolDefinitions) {
		return toolDefinitions.stream().map(toolDefinition -> {
			var function = new DeepSeekApi.FunctionTool.Function(toolDefinition.description(), toolDefinition.name(),
					toolDefinition.inputSchema());
			return new DeepSeekApi.FunctionTool(function);
		}).toList();
	}

	@Override
	public ChatOptions getDefaultOptions() {
		return DeepSeekChatOptions.fromOptions(this.defaultOptions);
	}

	@Override
	public String toString() {
		return "DeepSeekChatModel [defaultOptions=" + this.defaultOptions + "]";
	}

	/**
	 * Use the provided convention for reporting observation data
	 * @param observationConvention The provided convention
	 */
	public void setObservationConvention(ChatModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention cannot be null");
		this.observationConvention = observationConvention;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable DeepSeekApi deepSeekApi;

		private DeepSeekChatOptions defaultOptions = DeepSeekChatOptions.builder()
			.model(DeepSeekApi.DEFAULT_CHAT_MODEL)
			.temperature(0.7)
			.build();

		private @Nullable ToolCallingManager toolCallingManager;

		private ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate = new DefaultToolExecutionEligibilityPredicate();

		private RetryTemplate retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE;

		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		private Builder() {
		}

		public Builder deepSeekApi(DeepSeekApi deepSeekApi) {
			this.deepSeekApi = deepSeekApi;
			return this;
		}

		public Builder defaultOptions(DeepSeekChatOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
			this.toolCallingManager = toolCallingManager;
			return this;
		}

		public Builder toolExecutionEligibilityPredicate(
				ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate) {
			this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate;
			return this;
		}

		public Builder retryTemplate(RetryTemplate retryTemplate) {
			this.retryTemplate = retryTemplate;
			return this;
		}

		public Builder observationRegistry(ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		public DeepSeekChatModel build() {
			Assert.state(this.deepSeekApi != null, "DeepSeekApi must not be null");
			if (this.toolCallingManager != null) {
				return new DeepSeekChatModel(this.deepSeekApi, this.defaultOptions, this.toolCallingManager,
						this.retryTemplate, this.observationRegistry, this.toolExecutionEligibilityPredicate);
			}
			return new DeepSeekChatModel(this.deepSeekApi, this.defaultOptions, DEFAULT_TOOL_CALLING_MANAGER,
					this.retryTemplate, this.observationRegistry, this.toolExecutionEligibilityPredicate);
		}

	}

}
