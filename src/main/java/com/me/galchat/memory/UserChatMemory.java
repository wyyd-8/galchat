package com.me.galchat.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.me.galchat.constant.ChatConstant;
import com.me.galchat.constant.RedisConstant;
import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserChatHistory;
import com.me.galchat.domain.po.UserChatThinkingHistory;
import com.me.galchat.domain.po.UserChatToolCall;
import com.me.galchat.mapper.UserChatHistoryMapper;
import com.me.galchat.mapper.UserChatThinkingHistoryMapper;
import com.me.galchat.mapper.UserChatToolCallMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class UserChatMemory implements ChatMemory {

    private final UserChatHistoryMapper userChatHistoryMapper;
    private final UserChatThinkingHistoryMapper userChatThinkingHistoryMapper;
    private final UserChatToolCallMapper userChatToolCallMapper;
    private final StringRedisTemplate redisTemplate;
    private final boolean includeToolCalls;
    private final boolean includeAutoSearchInfo;
    private final boolean readOnly;

    /**
     * 根据 Builder 创建会话记忆实例。
     *
     * @param builder 包含 mapper、读取策略和只读配置的构造器
     */
    private UserChatMemory(Builder builder) {
        Assert.notNull(builder.userChatHistoryMapper, "userChatHistoryMapper cannot be null");
        this.userChatHistoryMapper = builder.userChatHistoryMapper;
        this.userChatThinkingHistoryMapper = builder.userChatThinkingHistoryMapper;
        this.userChatToolCallMapper = builder.userChatToolCallMapper;
        this.redisTemplate = builder.redisTemplate;
        this.includeToolCalls = builder.includeToolCalls;
        this.includeAutoSearchInfo = builder.includeAutoSearchInfo;
        this.readOnly = builder.readOnly;
    }

    /**
     * Spring AI ChatMemory 标准入口，批量追加消息。
     *
     * @param conversationId 字符串形式的会话标识，解析为 userWorldId/characterId/start
     * @param messages 待保存的消息列表
     * 调用来源：Spring AI 标准 ChatMemory 适配路径；当前 topic-aware 主链路主要使用 save/saveAssistantMessages。
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        if (readOnly) {
            return;
        }
        if (messages == null || messages.isEmpty()) {
            return;
        }

        ConversationInfo conversationInfo = new ConversationInfo(conversationId);
        for (Message message : messages) {
            if (MessageType.ASSISTANT.equals(message.getMessageType())) {
                saveAssistantMessage(conversationInfo, null, message);
            }
            else {
                save(conversationInfo, message);
            }
        }
    }

    /**
     * Spring AI ChatMemory 标准入口，读取会话历史消息。
     *
     * @param conversationId 字符串形式的会话标识，解析为 userWorldId/characterId/start
     * @return 组装后的 Spring AI Message 列表
     * 调用来源：Spring AI 标准 ChatMemory 适配路径。
     */
    @Override
    public List<Message> get(String conversationId) {
        ConversationInfo conversationInfo = new ConversationInfo(conversationId);
        return get(conversationInfo);
    }

    /**
     * 读取指定窗口内的历史，并按当前配置组装成模型请求消息。
     * 当窗口内 UserChatHistory 内容过长时，只返回 UserChatHistory 本身转换出的消息，
     * 不追加 reasoning/tool call/tool response 等辅助表内容，也不删除数据库内容。
     *
     * @param conversationInfo 会话定位信息，start 表示窗口起点
     * @return 组装后的 Spring AI Message 列表
     * 调用来源：TopicAwareMessageChatMemoryAdvisor.before。
     */
    List<Message> get(ConversationInfo conversationInfo) {
        List<UserChatHistory> histories = listHistories(conversationInfo);
        return toPromptMessages(histories);
    }

    List<Message> toPromptMessages(List<UserChatHistory> histories) {
        boolean includeAuxiliaryMessages = includeToolCalls
                && contextLength(histories) <= ChatConstant.MAX_CONTEXT_LENGTH;
        return toMessages(histories, includeAuxiliaryMessages);
    }

    /**
     * 查询指定会话窗口内可见的 UserChatHistory 行。
     *
     * @param conversationInfo 会话定位信息，start 表示最小历史 id
     * @return 按时间正序排列的历史行
     * 调用来源：get 和 TopicBoundaryService 的话题判断。
     */
    List<UserChatHistory> listHistories(ConversationInfo conversationInfo) {
        LambdaQueryWrapper<UserChatHistory> queryWrapper = new LambdaQueryWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, conversationInfo.getUserWorldId())
                .eq(UserChatHistory::getCharacterId, conversationInfo.getCharacterId())
                .ge(conversationInfo.getStart() != null, UserChatHistory::getId, conversationInfo.getStart())
                .and(wrapper -> wrapper.isNull(UserChatHistory::getType)
                        .or()
                        .notIn(UserChatHistory::getType, excludedTypes()))
                .orderByDesc(UserChatHistory::getId);
        List<UserChatHistory> histories = userChatHistoryMapper.selectList(queryWrapper);
        Collections.reverse(histories);
        return histories;
    }

    /**
     * 保存一条可见消息到 user_chat_history。
     *
     * @param conversationInfo 会话定位信息
     * @param message 待保存消息
     * @return 插入后的历史实体，包含数据库生成的 id
     * 调用来源：TopicAwareMessageChatMemoryAdvisor.before 保存当前用户消息。
     */
    UserChatHistory save(ConversationInfo conversationInfo, Message message) {
        Assert.isTrue(!readOnly, "read only chat memory cannot save messages");
        UserChatHistory userChatHistory = toVisibleUserChatHistory(conversationInfo, message);
        userChatHistoryMapper.insert(userChatHistory);
        initializeStepNoIfUserMessage(userChatHistory);
        deleteOneWithdrawnPlaceholderIfUserMessage(conversationInfo, userChatHistory);
        return userChatHistory;
    }

    private void deleteOneWithdrawnPlaceholderIfUserMessage(ConversationInfo conversationInfo,
                                                            UserChatHistory userChatHistory) {
        if (!isUserHistory(userChatHistory)) {
            return;
        }

        UserChatHistory withdrawnPlaceholder = userChatHistoryMapper.selectOne(new LambdaQueryWrapper<UserChatHistory>()
                .select(UserChatHistory::getId)
                .eq(UserChatHistory::getUserWorldId, conversationInfo.getUserWorldId())
                .eq(UserChatHistory::getCharacterId, conversationInfo.getCharacterId())
                .eq(UserChatHistory::getType, ChatConstant.WITHDRAWN_TYPE)
                .orderByDesc(UserChatHistory::getId)
                .last("limit 1"));
        if (withdrawnPlaceholder != null && withdrawnPlaceholder.getId() != null) {
            userChatHistoryMapper.deleteById(withdrawnPlaceholder.getId());
        }
    }

    /**
     * 保存模型返回的一批 assistant 消息，并关联到触发它们的用户消息。
     *
     * @param conversationInfo 会话定位信息
     * @param userMessageId 当前用户消息 id，用于绑定 step/reasoning/tool calls
     * @param messages assistant 消息列表
     * 调用来源：TopicAwareMessageChatMemoryAdvisor.after 和 saveToolExecution。
     */
    public void saveAssistantMessages(ConversationInfo conversationInfo, Long userMessageId, List<Message> messages) {
        if (readOnly || messages == null || messages.isEmpty()) {
            return;
        }

        for (Message message : messages) {
            saveAssistantMessage(conversationInfo, userMessageId, message);
        }
    }

    /**
     * 记录一次工具执行过程中的 assistant tool_call 消息和 tool result。
     *
     * @param conversationInfo 会话定位信息
     * @param userMessageId 当前用户消息 id
     * @param toolCallResponse 触发工具调用的模型响应，包含 assistant tool_calls
     * @param toolExecutionResult 工具执行结果，包含 ToolResponseMessage
     * 调用来源：RecordingToolCallingManager.executeToolCalls。
     */
    public void saveToolExecution(ConversationInfo conversationInfo, Long userMessageId,
                                  ChatResponse toolCallResponse, ToolExecutionResult toolExecutionResult) {
        if (readOnly || userMessageId == null) {
            return;
        }

        if (toolCallResponse != null) {
            List<Message> toolCallMessages = toolCallResponse.getResults()
                    .stream()
                    .map(Generation::getOutput)
                    .map(message -> (Message) message)
                    .toList();
            saveAssistantMessages(conversationInfo, userMessageId, toolCallMessages);
        }
        if (toolExecutionResult != null) {
            saveToolResponses(userMessageId, toolExecutionResult.conversationHistory());
        }
    }

    /**
     * 清空指定会话的可见历史和辅助表记录。
     *
     * @param conversationId 字符串形式的会话标识
     * 调用来源：Spring AI ChatMemory 标准清理入口。
     */
    @Override
    public void clear(String conversationId) {
        if (readOnly) {
            return;
        }
        ConversationInfo conversationInfo = new ConversationInfo(conversationId);
        List<Long> userMessageIds = selectUserMessageIds(conversationInfo);
        deleteAuxiliaryMessages(userMessageIds);
        deleteStepNoKeys(userMessageIds);
        userChatHistoryMapper.delete(new LambdaUpdateWrapper<UserChatHistory>()
                .eq(UserChatHistory::getUserWorldId, conversationInfo.getUserWorldId())
                .eq(UserChatHistory::getCharacterId, conversationInfo.getCharacterId()));
    }

    /**
     * 保存单条 assistant 消息。
     * 有 userMessageId 时，会把 content/reasoning/tool calls 绑定到同一个 step；
     * 没有 userMessageId 时，仅保存可见 content。
     *
     * @param conversationInfo 会话定位信息
     * @param userMessageId 触发该 assistant 消息的用户消息 id，可为 null
     * @param message assistant 消息
     */
    private void saveAssistantMessage(ConversationInfo conversationInfo, Long userMessageId, Message message) {
        if (userMessageId == null) {
            if (StringUtils.hasText(message.getText())) {
                userChatHistoryMapper.insert(toVisibleUserChatHistory(conversationInfo, message));
            }
            return;
        }

        if (message instanceof AssistantMessage assistantMessage) {
            if (userChatToolCallMapper != null && assistantMessage.hasToolCalls() && !hasNewToolCall(assistantMessage)) {
                return;
            }
            int stepNo = nextStepNo(userMessageId);
            saveThinkingIfPresent(userMessageId, stepNo, assistantMessage);
            saveToolCallsIfPresent(userMessageId, stepNo, assistantMessage);

            String visibleContent = trimAssistantVisiblePrefix(userMessageId, message.getText());
            if (StringUtils.hasText(visibleContent)) {
                userChatHistoryMapper.insert(toVisibleUserChatHistory(conversationInfo, userMessageId, stepNo,
                        message, visibleContent));
            }
        }
    }

    /**
     * 如果 assistant 消息是 DeepSeekAssistantMessage 且带 reasoning_content，则保存推理内容。
     *
     * @param userMessageId 用户消息 id
     * @param stepNo assistant 输出步骤号
     * @param assistantMessage assistant 消息
     */
    private void saveThinkingIfPresent(Long userMessageId, int stepNo, AssistantMessage assistantMessage) {
        if (!(assistantMessage instanceof DeepSeekAssistantMessage deepSeekAssistantMessage)
                || userChatThinkingHistoryMapper == null) {
            return;
        }

        String reasoningContent = deepSeekAssistantMessage.getReasoningContent();
        reasoningContent = trimAlreadySavedReasoning(userMessageId, reasoningContent);
        if (!StringUtils.hasText(reasoningContent)) {
            return;
        }

        UserChatThinkingHistory thinkingHistory = new UserChatThinkingHistory()
                .setUserMessageId(userMessageId)
                .setStepNo(stepNo)
                .setReasoningContent(reasoningContent);
        userChatThinkingHistoryMapper.insert(thinkingHistory);
    }

    /**
     * 流式工具调用会先单独保存 tool_call 前的 reasoning，最后的聚合响应又会带上整轮 reasoning。
     * 保存聚合响应时扣掉已落库的前缀，避免历史查询中重复展示同一段思考。
     *
     * @param userMessageId 用户消息 id
     * @param reasoningContent 当前 assistant 消息携带的 reasoning_content
     * @return 去掉已保存前缀后的新增 reasoning_content
     */
    private String trimAlreadySavedReasoning(Long userMessageId, String reasoningContent) {
        if (!StringUtils.hasText(reasoningContent) || userMessageId == null || userChatThinkingHistoryMapper == null) {
            return reasoningContent;
        }

        String savedReasoning = savedReasoningPrefix(userMessageId);
        if (!StringUtils.hasText(savedReasoning) || !reasoningContent.startsWith(savedReasoning)) {
            return reasoningContent;
        }
        return reasoningContent.substring(savedReasoning.length()).stripLeading();
    }

    /**
     * 按保存顺序拼出当前用户消息下已落库的 reasoning 前缀。
     *
     * @param userMessageId 用户消息 id
     * @return 已保存 reasoning_content 拼接结果
     */
    private String savedReasoningPrefix(Long userMessageId) {
        List<UserChatThinkingHistory> thinkingHistories = userChatThinkingHistoryMapper.selectList(
                new LambdaQueryWrapper<UserChatThinkingHistory>()
                        .eq(UserChatThinkingHistory::getUserMessageId, userMessageId)
                        .orderByAsc(UserChatThinkingHistory::getStepNo)
                        .orderByAsc(UserChatThinkingHistory::getId));
        StringBuilder builder = new StringBuilder();
        for (UserChatThinkingHistory thinkingHistory : thinkingHistories) {
            String content = thinkingHistory.getReasoningContent();
            if (content != null) {
                builder.append(content);
            }
        }
        return builder.toString();
    }

    /**
     * 流式工具调用会先单独保存 assistant 可见内容，最后的聚合响应又会带上整轮 assistant 可见内容。
     * 保存聚合响应时扣掉已落库的前缀，避免历史查询中重复展示同一段回复。
     *
     * @param userMessageId 用户消息 id
     * @param visibleContent assistant 可见文本
     * @return 去掉已保存 assistant 可见前缀后的新增可见文本
     */
    private String trimAssistantVisiblePrefix(Long userMessageId, String visibleContent) {
        if (!StringUtils.hasText(visibleContent) || userMessageId == null) {
            return visibleContent;
        }

        String savedVisibleContent = savedAssistantVisiblePrefix(userMessageId);
        if (!StringUtils.hasText(savedVisibleContent) || !visibleContent.startsWith(savedVisibleContent)) {
            return visibleContent;
        }
        return visibleContent.substring(savedVisibleContent.length()).stripLeading();
    }

    /**
     * 按保存顺序拼出当前用户消息下已落库的 assistant 可见内容前缀。
     *
     * @param userMessageId 用户消息 id
     * @return 已保存 assistant 可见内容拼接结果
     */
    private String savedAssistantVisiblePrefix(Long userMessageId) {
        List<UserChatHistory> assistantHistories = userChatHistoryMapper.selectList(
                new LambdaQueryWrapper<UserChatHistory>()
                        .eq(UserChatHistory::getUserMessageId, userMessageId)
                        .eq(UserChatHistory::getType, MessageType.ASSISTANT.getValue())
                        .orderByAsc(UserChatHistory::getStepNo)
                        .orderByAsc(UserChatHistory::getId));
        if (assistantHistories == null || assistantHistories.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (UserChatHistory assistantHistory : assistantHistories) {
            String content = assistantHistory.getContent();
            if (content != null) {
                builder.append(content);
            }
        }
        return builder.toString();
    }

    /**
     * 保存 assistant 消息中的 tool_calls。
     *
     * @param userMessageId 用户消息 id
     * @param stepNo assistant 输出步骤号
     * @param assistantMessage assistant 消息
     */
    private void saveToolCallsIfPresent(Long userMessageId, int stepNo, AssistantMessage assistantMessage) {
        if (userChatToolCallMapper == null || CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
            return;
        }

        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            if (!StringUtils.hasText(toolCall.id()) || hasToolCall(toolCall.id())) {
                continue;
            }
            UserChatToolCall userChatToolCall = new UserChatToolCall()
                    .setUserMessageId(userMessageId)
                    .setStepNo(stepNo)
                    .setToolCallId(toolCall.id())
                    .setToolName(toolCall.name())
                    .setToolArguments(toolCall.arguments());
            userChatToolCallMapper.insert(userChatToolCall);
        }
    }

    /**
     * 从工具执行后的 conversationHistory 中提取 ToolResponseMessage，并回填 tool result。
     *
     * @param userMessageId 用户消息 id
     * @param conversationHistory 工具执行后的对话历史
     */
    private void saveToolResponses(Long userMessageId, List<Message> conversationHistory) {
        if (userChatToolCallMapper == null || conversationHistory == null || conversationHistory.isEmpty()) {
            return;
        }

        for (Message message : conversationHistory) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                UserChatToolCall update = new UserChatToolCall().setToolResult(response.responseData());
                userChatToolCallMapper.update(update, new LambdaUpdateWrapper<UserChatToolCall>()
                        .eq(UserChatToolCall::getUserMessageId, userMessageId)
                        .eq(UserChatToolCall::getToolCallId, response.id()));
            }
        }
    }

    /**
     * 将一条可见消息转换为 UserChatHistory，不绑定用户消息 step。
     *
     * @param conversationInfo 会话定位信息
     * @param message 待转换消息
     * @return 可插入 user_chat_history 的实体
     */
    private UserChatHistory toVisibleUserChatHistory(ConversationInfo conversationInfo, Message message) {
        return toVisibleUserChatHistory(conversationInfo, null, null, message);
    }

    /**
     * 将一条 assistant 可见消息转换为 UserChatHistory，并绑定 userMessageId/stepNo。
     *
     * @param conversationInfo 会话定位信息
     * @param userMessageId 用户消息 id，可为 null
     * @param stepNo assistant 输出步骤号，可为 null
     * @param message 待转换消息
     * @return 可插入 user_chat_history 的实体
     */
    private UserChatHistory toVisibleUserChatHistory(ConversationInfo conversationInfo, Long userMessageId,
                                                     Integer stepNo, Message message) {
        return toVisibleUserChatHistory(conversationInfo, userMessageId, stepNo, message, message.getText());
    }

    /**
     * 将一条 assistant 可见消息转换为 UserChatHistory，并使用指定 content 入库。
     *
     * @param conversationInfo 会话定位信息
     * @param userMessageId 用户消息 id，可为 null
     * @param stepNo assistant 输出步骤号，可为 null
     * @param message 待转换消息
     * @param content 入库文本
     * @return 可插入 user_chat_history 的实体
     */
    private UserChatHistory toVisibleUserChatHistory(ConversationInfo conversationInfo, Long userMessageId,
                                                     Integer stepNo, Message message, String content) {
        return new UserChatHistory()
                .setUserWorldId(conversationInfo.getUserWorldId())
                .setCharacterId(conversationInfo.getCharacterId())
                .setContent(content)
                .setUserMessageId(userMessageId)
                .setStepNo(stepNo)
                .setType(message.getMessageType().getValue())
                .setTimestamp(LocalDateTime.now());
    }

    /**
     * 返回读取 user_chat_history 时需要排除的消息 type。
     *
     * @return 不参与 prompt 组装的 type 列表
     */
    private List<String> excludedTypes() {
        List<String> excludedTypes = new ArrayList<>(List.of(MessageType.SYSTEM.getValue(), MessageType.TOOL.getValue()));
        excludedTypes.add(ChatConstant.WITHDRAWN_TYPE);
        if (!includeAutoSearchInfo) {
            excludedTypes.add(ChatConstant.AUTO_SEARCH_INFO_TYPE);
        }
        return excludedTypes;
    }

    /**
     * 将 UserChatHistory 列表转换为模型消息，并按需插入辅助表中的 reasoning/tool calls/tool responses。
     *
     * @param histories 按时间正序排列的可见历史
     * @param includeAuxiliaryMessages 是否追加非 UserChatHistory 的辅助消息
     * @return 组装后的 Spring AI Message 列表
     */
    private List<Message> toMessages(List<UserChatHistory> histories, boolean includeAuxiliaryMessages) {
        if (!includeAuxiliaryMessages || histories.isEmpty()) {
            return histories.stream().map(this::toMessage).collect(Collectors.toCollection(ArrayList::new));
        }

        Set<Long> userMessageIds = histories.stream()
                .filter(this::isUserHistory)
                .map(UserChatHistory::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, List<UserChatToolCall>> toolCallsByUserMessageId = listToolCalls(userMessageIds);
        Map<Long, List<UserChatThinkingHistory>> thinkingByUserMessageId =
                listThinking(userMessageIds);

        List<Message> messages = new ArrayList<>();
        Set<AssistantStep> emittedSteps = new HashSet<>();
        Long currentUserMessageId = null;
        for (UserChatHistory history : histories) {
            if (isUserHistory(history)) {
                emitAuxiliaryMessages(currentUserMessageId, null, emittedSteps, messages,
                        thinkingByUserMessageId, toolCallsByUserMessageId);
                messages.add(toMessage(history));
                currentUserMessageId = history.getId();
            }
            else if (isLinkedAssistantHistory(history)) {
                emitAuxiliaryMessages(history.getUserMessageId(), history.getStepNo(), emittedSteps, messages,
                        thinkingByUserMessageId, toolCallsByUserMessageId);
                messages.add(toAssistantMessage(history.getContent(), reasoningContent(history.getUserMessageId(),
                        history.getStepNo(), thinkingByUserMessageId), stepToolCalls(history.getUserMessageId(),
                        history.getStepNo(), toolCallsByUserMessageId)));
                addToolResponsesIfPresent(history.getUserMessageId(), history.getStepNo(), messages,
                        toolCallsByUserMessageId);
                emittedSteps.add(new AssistantStep(history.getUserMessageId(), history.getStepNo()));
            }
            else {
                messages.add(toMessage(history));
            }
        }
        emitAuxiliaryMessages(currentUserMessageId, null, emittedSteps, messages,
                thinkingByUserMessageId, toolCallsByUserMessageId);
        return messages;
    }

    /**
     * 将指定用户消息下尚未发出的辅助 step 插入到 messages。
     *
     * @param userMessageId 用户消息 id
     * @param beforeStepNo 只发出小于该 step 的辅助消息；为 null 时发出全部剩余 step
     * @param emittedSteps 已经发出的 step 集合，用于去重
     * @param messages 正在组装的消息数组
     * @param thinkingByUserMessageId reasoning 内容分组
     * @param toolCallsByUserMessageId tool call 内容分组
     */
    private void emitAuxiliaryMessages(Long userMessageId, Integer beforeStepNo, Set<AssistantStep> emittedSteps,
                                       List<Message> messages,
                                       Map<Long, List<UserChatThinkingHistory>> thinkingByUserMessageId,
                                       Map<Long, List<UserChatToolCall>> toolCallsByUserMessageId) {
        if (userMessageId == null) {
            return;
        }

        stepNos(userMessageId, thinkingByUserMessageId, toolCallsByUserMessageId)
                .stream()
                .filter(stepNo -> beforeStepNo == null || stepNo < beforeStepNo)
                .sorted()
                .forEach(stepNo -> {
                    AssistantStep assistantStep = new AssistantStep(userMessageId, stepNo);
                    if (!emittedSteps.add(assistantStep)) {
                        return;
                    }

                    messages.add(toAssistantMessage("", reasoningContent(userMessageId, stepNo, thinkingByUserMessageId),
                            stepToolCalls(userMessageId, stepNo, toolCallsByUserMessageId)));
                    addToolResponsesIfPresent(userMessageId, stepNo, messages, toolCallsByUserMessageId);
                });
    }

    /**
     * 计算某条用户消息下所有有辅助内容的 stepNo。
     *
     * @param userMessageId 用户消息 id
     * @param thinkingByUserMessageId reasoning 内容分组
     * @param toolCallsByUserMessageId tool call 内容分组
     * @return stepNo 集合
     */
    private Set<Integer> stepNos(Long userMessageId,
                                 Map<Long, List<UserChatThinkingHistory>> thinkingByUserMessageId,
                                 Map<Long, List<UserChatToolCall>> toolCallsByUserMessageId) {
        Set<Integer> stepNos = thinkingByUserMessageId.getOrDefault(userMessageId, List.of())
                .stream()
                .map(UserChatThinkingHistory::getStepNo)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        toolCallsByUserMessageId.getOrDefault(userMessageId, List.of())
                .stream()
                .map(UserChatToolCall::getStepNo)
                .filter(Objects::nonNull)
                .forEach(stepNos::add);
        return stepNos;
    }

    /**
     * 查找指定 userMessageId/stepNo 对应的 reasoning_content。
     *
     * @param userMessageId 用户消息 id
     * @param stepNo assistant 输出步骤号
     * @param thinkingByUserMessageId reasoning 内容分组
     * @return reasoning_content；不存在时返回 null
     */
    private String reasoningContent(Long userMessageId, Integer stepNo,
                                    Map<Long, List<UserChatThinkingHistory>> thinkingByUserMessageId) {
        return thinkingByUserMessageId.getOrDefault(userMessageId, List.of())
                .stream()
                .filter(thinking -> Objects.equals(stepNo, thinking.getStepNo()))
                .map(UserChatThinkingHistory::getReasoningContent)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    /**
     * 查找指定 userMessageId/stepNo 对应的 tool_calls，并转换为 Spring AI ToolCall。
     *
     * @param userMessageId 用户消息 id
     * @param stepNo assistant 输出步骤号
     * @param toolCallsByUserMessageId tool call 内容分组
     * @return assistant tool call 列表
     */
    private List<AssistantMessage.ToolCall> stepToolCalls(Long userMessageId, Integer stepNo,
                                                          Map<Long, List<UserChatToolCall>> toolCallsByUserMessageId) {
        return toolCallsByUserMessageId.getOrDefault(userMessageId, List.of())
                .stream()
                .filter(toolCall -> Objects.equals(stepNo, toolCall.getStepNo()))
                .map(toolCall -> new AssistantMessage.ToolCall(toolCall.getToolCallId(), "function",
                        toolCall.getToolName(), toolCall.getToolArguments()))
                .toList();
    }

    /**
     * 根据 content/reasoning/toolCalls 构造 assistant 消息。
     *
     * @param content assistant 可见内容
     * @param reasoningContent DeepSeek reasoning_content，可为 null
     * @param toolCalls assistant tool_calls
     * @return AssistantMessage 或 DeepSeekAssistantMessage
     */
    private Message toAssistantMessage(String content, String reasoningContent,
                                       List<AssistantMessage.ToolCall> toolCalls) {
        String text = content == null ? "" : content;
        if (StringUtils.hasText(reasoningContent)) {
            return new DeepSeekAssistantMessage.Builder()
                    .content(text)
                    .reasoningContent(reasoningContent)
                    .toolCalls(toolCalls)
                    .build();
        }
        return AssistantMessage.builder()
                .content(text)
                .toolCalls(toolCalls)
                .build();
    }

    /**
     * 将指定 step 的 tool result 作为 ToolResponseMessage 追加到消息数组。
     *
     * @param userMessageId 用户消息 id
     * @param stepNo assistant 输出步骤号
     * @param messages 正在组装的消息数组
     * @param toolCallsByUserMessageId tool call 内容分组
     */
    private void addToolResponsesIfPresent(Long userMessageId, Integer stepNo, List<Message> messages,
                                           Map<Long, List<UserChatToolCall>> toolCallsByUserMessageId) {
        List<ToolResponseMessage.ToolResponse> responses = toolCallsByUserMessageId.getOrDefault(userMessageId, List.of())
                .stream()
                .filter(toolCall -> Objects.equals(stepNo, toolCall.getStepNo()))
                .filter(toolCall -> toolCall.getToolResult() != null)
                .map(toolCall -> new ToolResponseMessage.ToolResponse(toolCall.getToolCallId(),
                        toolCall.getToolName(), toolCall.getToolResult()))
                .toList();
        if (!responses.isEmpty()) {
            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }
    }

    /**
     * 将单条 UserChatHistory 转换为基础 Spring AI Message。
     *
     * @param history 历史行
     * @return 对应的 SystemMessage/AssistantMessage/UserMessage
     */
    private Message toMessage(UserChatHistory history) {
        String content = history.getContent();
        String type = normalizeType(history.getType());

        if (MessageType.SYSTEM.getValue().equals(type)) {
            return new SystemMessage(content);
        }
        if (MessageType.ASSISTANT.getValue().equals(type)) {
            return new AssistantMessage(content);
        }
        if (ChatConstant.AUTO_SEARCH_INFO_TYPE.equals(type)) {
            return new UserMessage(content);
        }
        return new UserMessage(content);
    }

    /**
     * 计算当前窗口内 UserChatHistory content 的总长度。
     *
     * @param histories 历史行列表
     * @return content 字符数总和
     */
    private int contextLength(List<UserChatHistory> histories) {
        int length = 0;
        for (UserChatHistory history : histories) {
            String content = history.getContent();
            if (content != null) {
                length += content.length();
            }
        }
        return length;
    }

    /**
     * 判断历史行是否是真实用户消息。
     *
     * @param history 历史行
     * @return true 表示 type 为 user 或空 type
     */
    private boolean isUserHistory(UserChatHistory history) {
        return MessageType.USER.getValue().equals(normalizeType(history.getType()));
    }

    /**
     * 判断 assistant 可见消息是否已绑定 userMessageId/stepNo。
     *
     * @param history 历史行
     * @return true 表示可与 reasoning/tool calls 合并为同一 assistant step
     */
    private boolean isLinkedAssistantHistory(UserChatHistory history) {
        return MessageType.ASSISTANT.getValue().equals(normalizeType(history.getType()))
                && history.getUserMessageId() != null
                && history.getStepNo() != null;
    }

    /**
     * 批量查询用户消息下的 tool_calls。
     *
     * @param userMessageIds 用户消息 id 集合
     * @return 按 userMessageId 分组的 tool call 列表
     */
    private Map<Long, List<UserChatToolCall>> listToolCalls(Set<Long> userMessageIds) {
        if (userChatToolCallMapper == null || userMessageIds.isEmpty()) {
            return Map.of();
        }

        List<UserChatToolCall> toolCalls = userChatToolCallMapper.selectList(new LambdaQueryWrapper<UserChatToolCall>()
                .in(UserChatToolCall::getUserMessageId, userMessageIds)
                .orderByAsc(UserChatToolCall::getStepNo)
                .orderByAsc(UserChatToolCall::getId));
        return toolCalls.stream().collect(Collectors.groupingBy(UserChatToolCall::getUserMessageId));
    }

    /**
     * 批量查询用户消息下的 reasoning_content。
     *
     * @param userMessageIds 用户消息 id 集合
     * @return 按 userMessageId 分组的 thinking 列表
     */
    private Map<Long, List<UserChatThinkingHistory>> listThinking(Set<Long> userMessageIds) {
        if (userChatThinkingHistoryMapper == null || userMessageIds.isEmpty()) {
            return Map.of();
        }

        List<UserChatThinkingHistory> thinkingHistories = userChatThinkingHistoryMapper.selectList(
                new LambdaQueryWrapper<UserChatThinkingHistory>()
                        .in(UserChatThinkingHistory::getUserMessageId, userMessageIds)
                        .orderByAsc(UserChatThinkingHistory::getStepNo)
                        .orderByAsc(UserChatThinkingHistory::getId));
        return thinkingHistories.stream().collect(Collectors.groupingBy(UserChatThinkingHistory::getUserMessageId));
    }

    /**
     * 计算指定用户消息下下一条 assistant 输出的 stepNo。
     *
     * @param userMessageId 用户消息 id
     * @return 下一步 stepNo
     */
    private int nextStepNo(Long userMessageId) {
        Integer redisStepNo = nextRedisStepNo(userMessageId);
        if (redisStepNo != null) {
            return redisStepNo;
        }

        int thinkingStepNo = latestThinkingStepNo(userMessageId);
        int toolStepNo = latestToolStepNo(userMessageId);
        int stepNo = Math.max(thinkingStepNo, toolStepNo) + 1;
        cacheStepNo(userMessageId, stepNo);
        return stepNo;
    }

    /**
     * 用户消息保存后初始化 Redis step 计数器，让后续 assistant step 使用 INCR 避免查询最大 stepNo。
     *
     * @param userChatHistory 已保存的可见历史行
     */
    private void initializeStepNoIfUserMessage(UserChatHistory userChatHistory) {
        if (redisTemplate == null || userChatHistory.getId() == null || !isUserHistory(userChatHistory)) {
            return;
        }

        cacheStepNo(userChatHistory.getId(), 0);
    }

    /**
     * 通过 Redis 原子自增获取下一条 assistant 输出的 stepNo。
     *
     * @param userMessageId 用户消息 id
     * @return 下一步 stepNo；Redis 不可用时返回 null
     */
    private Integer nextRedisStepNo(Long userMessageId) {
        if (redisTemplate == null) {
            return null;
        }

        String key = buildStepNoKey(userMessageId);
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                return null;
            }
            Long stepNo = redisTemplate.opsForValue().increment(key);
            if (stepNo == null) {
                return null;
            }
            redisTemplate.expire(key, RedisConstant.CHAT_MEMORY_STEP_TTL);
            return stepNo.intValue();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 将数据库回退计算出的 stepNo 写回 Redis，避免同一用户消息后续 step 再查库。
     *
     * @param userMessageId 用户消息 id
     * @param stepNo 当前最新 stepNo
     */
    private void cacheStepNo(Long userMessageId, int stepNo) {
        if (redisTemplate == null) {
            return;
        }

        try {
            redisTemplate.opsForValue().set(buildStepNoKey(userMessageId), String.valueOf(stepNo),
                    RedisConstant.CHAT_MEMORY_STEP_TTL);
        } catch (RuntimeException ignored) {
            // Redis 只是 stepNo 快路径；缓存失败不影响数据库持久化。
        }
    }

    /**
     * 查询指定用户消息下已保存 reasoning_content 的最大 stepNo。
     *
     * @param userMessageId 用户消息 id
     * @return 最大 stepNo；不存在时返回 0
     */
    private int latestThinkingStepNo(Long userMessageId) {
        if (userChatThinkingHistoryMapper == null) {
            return 0;
        }
        UserChatThinkingHistory latestThinking = userChatThinkingHistoryMapper.selectOne(
                new LambdaQueryWrapper<UserChatThinkingHistory>()
                        .eq(UserChatThinkingHistory::getUserMessageId, userMessageId)
                        .orderByDesc(UserChatThinkingHistory::getStepNo)
                        .last("limit 1"));
        return latestThinking == null || latestThinking.getStepNo() == null ? 0 : latestThinking.getStepNo();
    }

    /**
     * 查询指定用户消息下已保存 tool_call 的最大 stepNo。
     *
     * @param userMessageId 用户消息 id
     * @return 最大 stepNo；不存在时返回 0
     */
    private int latestToolStepNo(Long userMessageId) {
        if (userChatToolCallMapper == null) {
            return 0;
        }
        UserChatToolCall latestToolCall = userChatToolCallMapper.selectOne(new LambdaQueryWrapper<UserChatToolCall>()
                .eq(UserChatToolCall::getUserMessageId, userMessageId)
                .orderByDesc(UserChatToolCall::getStepNo)
                .last("limit 1"));
        return latestToolCall == null || latestToolCall.getStepNo() == null ? 0 : latestToolCall.getStepNo();
    }

    /**
     * 判断指定 tool_call id 是否已经保存。
     *
     * @param toolCallId 模型返回的 tool_call id
     * @return true 表示已存在
     */
    private boolean hasToolCall(String toolCallId) {
        return userChatToolCallMapper.selectCount(new LambdaQueryWrapper<UserChatToolCall>()
                .eq(UserChatToolCall::getToolCallId, toolCallId)) > 0;
    }

    /**
     * 判断 assistant 消息里是否包含尚未保存的新 tool_call。
     *
     * @param assistantMessage assistant 消息
     * @return true 表示至少有一个新 tool_call
     */
    private boolean hasNewToolCall(AssistantMessage assistantMessage) {
        if (userChatToolCallMapper == null || CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
            return false;
        }
        return assistantMessage.getToolCalls()
                .stream()
                .map(AssistantMessage.ToolCall::id)
                .filter(StringUtils::hasText)
                .anyMatch(toolCallId -> !hasToolCall(toolCallId));
    }

    /**
     * 查询指定会话中所有用户消息 id。
     *
     * @param conversationInfo 会话定位信息
     * @return 用户消息 id 列表
     * 调用来源：clear 清理辅助表。
     */
    private List<Long> selectUserMessageIds(ConversationInfo conversationInfo) {
        return userChatHistoryMapper.selectList(new LambdaQueryWrapper<UserChatHistory>()
                .select(UserChatHistory::getId)
                .eq(UserChatHistory::getUserWorldId, conversationInfo.getUserWorldId())
                .eq(UserChatHistory::getCharacterId, conversationInfo.getCharacterId())
                .eq(UserChatHistory::getType, MessageType.USER.getValue())).stream()
                .map(UserChatHistory::getId)
                .toList();
    }

    /**
     * 删除指定用户消息关联的 reasoning/tool call 辅助表记录。
     *
     * @param userMessageIds 用户消息 id 列表
     * 调用来源：clear 清空整个会话时使用。
     */
    private void deleteAuxiliaryMessages(List<Long> userMessageIds) {
        if (userMessageIds.isEmpty()) {
            return;
        }
        if (userChatThinkingHistoryMapper != null) {
            userChatThinkingHistoryMapper.delete(new LambdaUpdateWrapper<UserChatThinkingHistory>()
                    .in(UserChatThinkingHistory::getUserMessageId, userMessageIds));
        }
        if (userChatToolCallMapper != null) {
            userChatToolCallMapper.delete(new LambdaUpdateWrapper<UserChatToolCall>()
                    .in(UserChatToolCall::getUserMessageId, userMessageIds));
        }
    }

    /**
     * 删除指定用户消息关联的 Redis step 计数器。
     *
     * @param userMessageIds 用户消息 id 列表
     * 调用来源：clear 清空整个会话时使用。
     */
    private void deleteStepNoKeys(List<Long> userMessageIds) {
        if (redisTemplate == null || userMessageIds.isEmpty()) {
            return;
        }

        List<String> keys = userMessageIds.stream()
                .map(this::buildStepNoKey)
                .toList();
        try {
            redisTemplate.delete(keys);
        } catch (RuntimeException ignored) {
            // 清理缓存失败不影响数据库清空。
        }
    }

    /**
     * 构建指定用户消息的 Redis step 计数 key。
     *
     * @param userMessageId 用户消息 id
     * @return Redis key
     */
    private String buildStepNoKey(Long userMessageId) {
        return RedisConstant.CHAT_MEMORY_STEP_KEY_PREFIX + userMessageId;
    }

    /**
     * 归一化消息 type，空 type 按 user 处理。
     *
     * @param type 原始 type
     * @return 小写 type
     */
    private String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            return MessageType.USER.getValue();
        }
        return type.toLowerCase(Locale.ROOT);
    }

    /**
     * 标识某条用户消息下的一次 assistant 输出 step。
     *
     * @param userMessageId 用户消息 id
     * @param stepNo assistant 输出步骤号
     */
    private record AssistantStep(Long userMessageId, Integer stepNo) {
    }

    /**
     * 创建 UserChatMemory 构造器。
     *
     * @param userChatHistoryMapper 可见历史 mapper
     * @return Builder
     */
    public static Builder builder(UserChatHistoryMapper userChatHistoryMapper) {
        return new Builder(userChatHistoryMapper);
    }

    /**
     * UserChatMemory 构造器，用于配置 mapper、读取策略和只读模式。
     */
    public static class Builder {

        private final UserChatHistoryMapper userChatHistoryMapper;
        private UserChatThinkingHistoryMapper userChatThinkingHistoryMapper;
        private UserChatToolCallMapper userChatToolCallMapper;
        private StringRedisTemplate redisTemplate;
        private boolean includeToolCalls;
        private boolean includeAutoSearchInfo = true;
        private boolean readOnly;

        /**
         * 创建 Builder。
         *
         * @param userChatHistoryMapper 可见历史 mapper
         */
        private Builder(UserChatHistoryMapper userChatHistoryMapper) {
            this.userChatHistoryMapper = userChatHistoryMapper;
        }

        /**
         * 配置 reasoning_content 辅助表 mapper。
         *
         * @param userChatThinkingHistoryMapper thinking history mapper
         * @return 当前 Builder
         */
        public Builder thinkingHistoryMapper(UserChatThinkingHistoryMapper userChatThinkingHistoryMapper) {
            this.userChatThinkingHistoryMapper = userChatThinkingHistoryMapper;
            return this;
        }

        /**
         * 配置 tool_call 辅助表 mapper。
         *
         * @param userChatToolCallMapper tool call mapper
         * @return 当前 Builder
         */
        public Builder toolCallMapper(UserChatToolCallMapper userChatToolCallMapper) {
            this.userChatToolCallMapper = userChatToolCallMapper;
            return this;
        }

        /**
         * 配置 Redis，用于保存当前用户消息下的 assistant step 计数。
         *
         * @param redisTemplate Redis 字符串模板；为空时回退数据库计算 stepNo
         * @return 当前 Builder
         */
        public Builder redisTemplate(StringRedisTemplate redisTemplate) {
            this.redisTemplate = redisTemplate;
            return this;
        }

        /**
         * 配置读取历史时是否追加 reasoning/tool calls/tool responses。
         *
         * @param includeToolCalls true 表示追加辅助消息
         * @return 当前 Builder
         */
        public Builder includeToolCalls(boolean includeToolCalls) {
            this.includeToolCalls = includeToolCalls;
            return this;
        }

        /**
         * 配置读取历史时是否包含 auto_search_info。
         *
         * @param includeAutoSearchInfo true 表示主 AI 可读取自动检索信息
         * @return 当前 Builder
         */
        public Builder includeAutoSearchInfo(boolean includeAutoSearchInfo) {
            this.includeAutoSearchInfo = includeAutoSearchInfo;
            return this;
        }

        /**
         * 配置只读模式。
         *
         * @param readOnly true 表示跳过所有写入和清理
         * @return 当前 Builder
         */
        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        /**
         * 构建 UserChatMemory。
         *
         * @return UserChatMemory 实例
         */
        public UserChatMemory build() {
            return new UserChatMemory(this);
        }
    }
}
