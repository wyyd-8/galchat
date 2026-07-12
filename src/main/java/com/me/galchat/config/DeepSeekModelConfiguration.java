package com.me.galchat.config;

import com.me.galchat.interceptor.DeepSeekThinkingInterceptor;
import com.me.galchat.memory.UserChatMemory;
import com.me.galchat.model.DeepSeekChatModel;
import com.me.galchat.tool.RecordingToolCallingManager;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatProperties;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekConnectionProperties;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class DeepSeekModelConfiguration {

    @Bean
    @Primary
    public DeepSeekChatModel deepSeekThinkingChatModel(
            DeepSeekConnectionProperties connectionProperties,
            DeepSeekChatProperties chatProperties,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ToolCallingManager toolCallingManager,
            UserChatMemory userChatMemory,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObjectProvider<ResponseErrorHandler> responseErrorHandler,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention,
            ObjectProvider<ToolExecutionEligibilityPredicate> toolExecutionEligibilityPredicate) {
        return buildChatModel(connectionProperties, chatProperties,
                restClientBuilderProvider.getIfAvailable(RestClient::builder),
                webClientBuilderProvider.getIfAvailable(WebClient::builder),
                new RecordingToolCallingManager(toolCallingManager, userChatMemory),
                retryTemplate, responseErrorHandler, observationRegistry,
                observationConvention, toolExecutionEligibilityPredicate);
    }

    @Bean
    public DeepSeekChatModel deepSeekNonThinkingChatModel(
            DeepSeekConnectionProperties connectionProperties,
            DeepSeekChatProperties chatProperties,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ToolCallingManager toolCallingManager,
            UserChatMemory userChatMemory,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObjectProvider<ResponseErrorHandler> responseErrorHandler,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention,
            ObjectProvider<ToolExecutionEligibilityPredicate> toolExecutionEligibilityPredicate) {
        RestClient.Builder restClientBuilder = restClientBuilderProvider
                .getIfAvailable(RestClient::builder)
                .clone()
                .requestInterceptor(new DeepSeekThinkingInterceptor());

        return buildChatModel(connectionProperties, chatProperties, restClientBuilder,
                webClientBuilderProvider.getIfAvailable(WebClient::builder),
                new RecordingToolCallingManager(toolCallingManager, userChatMemory),
                retryTemplate, responseErrorHandler, observationRegistry,
                observationConvention, toolExecutionEligibilityPredicate);
    }

    /**
     * 群聊模型不使用 RecordingToolCallingManager。即使调用方未来添加工具，工具执行也不会隐式写入
     * UserChatMemory；群聊必须使用自己的 message/step 关联记录。
     */
    @Bean
    public DeepSeekChatModel groupDeepSeekThinkingChatModel(
            DeepSeekConnectionProperties connectionProperties,
            DeepSeekChatProperties chatProperties,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ToolCallingManager toolCallingManager,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObjectProvider<ResponseErrorHandler> responseErrorHandler,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention,
            ObjectProvider<ToolExecutionEligibilityPredicate> toolExecutionEligibilityPredicate) {
        return buildChatModel(connectionProperties, chatProperties,
                restClientBuilderProvider.getIfAvailable(RestClient::builder),
                webClientBuilderProvider.getIfAvailable(WebClient::builder),
                toolCallingManager, retryTemplate, responseErrorHandler, observationRegistry,
                observationConvention, toolExecutionEligibilityPredicate);
    }

    @Bean
    public DeepSeekChatModel groupDeepSeekNonThinkingChatModel(
            DeepSeekConnectionProperties connectionProperties,
            DeepSeekChatProperties chatProperties,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ToolCallingManager toolCallingManager,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObjectProvider<ResponseErrorHandler> responseErrorHandler,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention,
            ObjectProvider<ToolExecutionEligibilityPredicate> toolExecutionEligibilityPredicate) {
        RestClient.Builder restClientBuilder = restClientBuilderProvider
                .getIfAvailable(RestClient::builder)
                .clone()
                .requestInterceptor(new DeepSeekThinkingInterceptor());
        return buildChatModel(connectionProperties, chatProperties, restClientBuilder,
                webClientBuilderProvider.getIfAvailable(WebClient::builder),
                toolCallingManager, retryTemplate, responseErrorHandler, observationRegistry,
                observationConvention, toolExecutionEligibilityPredicate);
    }

    private DeepSeekChatModel buildChatModel(
            DeepSeekConnectionProperties connectionProperties,
            DeepSeekChatProperties chatProperties,
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder,
            ToolCallingManager toolCallingManager,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObjectProvider<ResponseErrorHandler> responseErrorHandler,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention,
            ObjectProvider<ToolExecutionEligibilityPredicate> toolExecutionEligibilityPredicate) {
        DeepSeekApi deepSeekApi = buildDeepSeekApi(connectionProperties, chatProperties,
                restClientBuilder, webClientBuilder, responseErrorHandler);

        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(chatProperties.getOptions())
                .toolCallingManager(toolCallingManager)
                .toolExecutionEligibilityPredicate(toolExecutionEligibilityPredicate
                        .getIfUnique(DefaultToolExecutionEligibilityPredicate::new))
                .retryTemplate(retryTemplate.getIfUnique(() -> RetryUtils.DEFAULT_RETRY_TEMPLATE))
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();
        observationConvention.ifAvailable(chatModel::setObservationConvention);
        return chatModel;
    }

    private DeepSeekApi buildDeepSeekApi(
            DeepSeekConnectionProperties connectionProperties,
            DeepSeekChatProperties chatProperties,
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder,
            ObjectProvider<ResponseErrorHandler> responseErrorHandler) {
        String baseUrl = StringUtils.hasText(chatProperties.getBaseUrl())
                ? chatProperties.getBaseUrl()
                : connectionProperties.getBaseUrl();
        Assert.hasText(baseUrl, "DeepSeek base URL must be set");

        String apiKey = StringUtils.hasText(chatProperties.getApiKey())
                ? chatProperties.getApiKey()
                : connectionProperties.getApiKey();
        Assert.hasText(apiKey, "DeepSeek API key must be set");

        return DeepSeekApi.builder()
                .baseUrl(baseUrl)
                .apiKey(new SimpleApiKey(apiKey))
                .completionsPath(chatProperties.getCompletionsPath())
                .betaPrefixPath(chatProperties.getBetaPrefixPath())
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .responseErrorHandler(responseErrorHandler.getIfAvailable(() -> RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER))
                .build();
    }
}
