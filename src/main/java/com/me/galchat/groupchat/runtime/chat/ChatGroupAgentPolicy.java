package com.me.galchat.groupchat.runtime.chat;

import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import com.me.galchat.groupchat.runtime.GroupAgentPolicy;
import com.me.galchat.groupchat.runtime.GroupContextMaterial;
import com.me.galchat.groupchat.runtime.GroupModelInvocation;
import com.me.galchat.service.impl.group.GroupContextAssembler;
import com.me.galchat.tool.UserCharacterFavorTools;
import com.me.galchat.tool.VectorTools;
import com.me.galchat.tool.TrpgRunMemoryTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class ChatGroupAgentPolicy implements GroupAgentPolicy {

    private final ChatClient chatClient;
    private final GroupContextAssembler contextAssembler;
    private final VectorTools vectorTools;
    private final UserCharacterFavorTools favorTools;
    private final TrpgRunMemoryTools runMemoryTools;

    public ChatGroupAgentPolicy(@Qualifier("chatGroupChatClient") ChatClient chatClient,
                                GroupContextAssembler contextAssembler,
                                VectorTools vectorTools,
                                UserCharacterFavorTools favorTools,
                                TrpgRunMemoryTools runMemoryTools) {
        this.chatClient = chatClient;
        this.contextAssembler = contextAssembler;
        this.vectorTools = vectorTools;
        this.favorTools = favorTools;
        this.runMemoryTools = runMemoryTools;
    }

    @Override
    public GroupModelInvocation prepare(GroupConversation conversation, GroupActionSpec action,
                                        GroupContextMaterial context) {
        String name = actorName(conversation.getUserWorldId(), action.actor());
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(contextAssembler.baseSystemPrompt(conversation, action.actor()) + """

                你正在一个多人群聊中扮演%s。
                聊天记录中的 speaker 标记是真实发言者身份；其他角色的消息不是你的经历或台词。
                不得输出隐藏思考过程。

                【群聊中的记忆检索】
                回复前，先理解用户本轮的问题，再结合其他成员的发言，判断自己准备回应哪些内容，以及这些内容是否仍有信息缺口。
                其他成员的发言可以作为交流上下文，但不等于你已经检索或确认了相关记忆。其他成员提供了部分信息，不代表用户的问题已经得到完整回答，也不代表与你自身经历相关的信息已经得到确认。
                - 当前上下文已经明确支持的内容，可以直接使用，无需重复检索。
                - 如果你准备补充的过去事件、约定、地点、人物关系或个人经历缺少明确依据，必须先调用对应的记忆检索工具查询缺失部分。
                - 如果其他成员只回答了问题的一部分，而你需要回应的部分仍未确认，必须针对剩余缺口检索，不能仅因前面已有相关发言就跳过。
                - 如果你只是对已经明确的信息表达态度、感受或简单接话，不需要为了发言而检索。
                - 不得把其他成员的个人经历当作自己的记忆，也不得从局部线索推断出未经确认的细节。
                - 检索后仍无法确认时，只回应有依据的部分，对其余内容自然表达不确定。

                【记忆工具选择】
                群聊中需要补全记忆时，按信息所属类型选择工具；基础规则中要求调用 searchInfo 的记忆查询，涉及跑团时应使用以下对应的跑团工具。
                - 普通聊天记忆、世界设定或非跑团经历，使用 searchInfo。
                - 查询参与过哪些跑团、查找更早的跑团或确认跑团及参与者对应关系，使用 listTrpgRuns。
                - 查询指定跑团的当前状态、自己的调查员人物卡、骰运统计、场景或已结束跑团的总结，使用 getTrpgRunDetails。
                - 回忆指定跑团中发生过的具体事件或对话，使用 searchTrpgChatRounds，针对缺失内容填写关键词。
                - <recent-trpg-runs> 只提供最近跑团的简要索引，不代表已知具体剧情。当前索引或上下文已能明确确定目标跑团 runId 及所需参与者对应关系时，可直接调用对应的详情或事件检索工具，无需先调用 listTrpgRuns；无法确定时，先调用 listTrpgRuns 确认，不要猜测 runId。
                """.formatted(name)));
        messages.addAll(context.messages());
        messages.add(new UserMessage("现在轮到" + name + "回复。只生成" + name
                + "本人的言语、动作或感受，不要代替用户或其他角色发言，不要输出发言者标签。"));
        return new GroupModelInvocation(chatClient, new Prompt(messages),
                List.of(vectorTools, favorTools, runMemoryTools));
    }

    @Override
    public String actorName(Long userWorldId, GroupActorRef actor) {
        return contextAssembler.actorName(userWorldId, actor);
    }
}
