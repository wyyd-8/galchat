package com.me.galchat.tool;

import com.me.galchat.vector.MutiSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VectorTools {
    private final MutiSearchService mutiSearchService;

    @Tool(description = """
            当你提及"让我搜索一下相关的记忆"或"需要确认一下记忆"，必须调用此方法！
            
            工具描述：
            根据重写后的字符串，从多个数据库中匹配与其近似的内容。
            使用流程：
            1、先判断当前回复是否依赖缺失记忆或外部上下文。如果依赖，先调用本工具，不要直接猜测。
            2、重写你希望查找的内容，使其更适合进行向量匹配，只概述想查询的陈述句事件，不要添加询问内容。
            重写后的字符串应该与当前对话相关，但可以进行适当扩展；如果涉及人名、地点、物品、事件或时间，应当写清楚。
            3、调用此方法，传入重写后的字符串，得到对应的返回值。
            注意事项：
            此方法的返回值是一个字符串，包含了从数据库中匹配到的与字符串相关的内容。返回值的格式如下：
            来源: [来源名称]
            时间戳: [匹配到的内容被创建的时间戳，部分来源可能为"未知"]
            内容: [匹配到的内容]
            不同来源的内容之间用以下分隔符分隔:---
            查询到的内容可能无关联，或为空字符串，此时请忽略返回内容。
            同一轮对话中，如果存在多个彼此独立的明确信息缺口，可以分别调用本工具；不要重复查询同一信息来扩大结果量。
            工具结果不足时，不要编造确定事实，应以角色口吻谨慎回应。
            """)
    public String searchInfo(@ToolParam(description = "重写后的字符串") String query, ToolContext context) {
        return mutiSearchService.searchInfo(query, context);
    }

}
