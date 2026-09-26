package com.me.galchat.memory;

import tools.jackson.databind.json.JsonMapper;
import java.util.HashSet;
import java.util.List;

/** 发言者和顺序由原记录决定，模型只提供逐条正文。 */
public final class TopicSummaryCodec {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private TopicSummaryCodec() { }

    public record Item(String messageId, String speaker, String timestamp, String content) { }

    public static String input(List<Item> messages) {
        return JSON.writeValueAsString(messages);
    }

    public static String render(List<Item> source, String response) {
        try {
            var items = JSON.readTree(response).get("messages");
            if (items == null || !items.isArray() || items.size() != source.size()) {
                throw new IllegalArgumentException("摘要条数与原消息不一致");
            }
            var seen = new HashSet<String>();
            var text = new StringBuilder();
            for (int i = 0; i < source.size(); i++) {
                Item original = source.get(i);
                var item = items.get(i);
                var id = item.get("messageId");
                var content = item.get("compressedContent");
                if (id == null || !id.isString() || !original.messageId().equals(id.stringValue())
                        || !seen.add(id.stringValue()) || content == null || !content.isString()
                        || content.stringValue().isBlank()) {
                    throw new IllegalArgumentException("摘要消息标识、顺序或正文无效");
                }
                String summary = content.stringValue().strip();
                // 扩写不是压缩：保留原句，避免摘要意外膨胀。
                if (summary.length() > original.content().length()) {
                    summary = original.content();
                }
                if (original.timestamp() != null && !original.timestamp().isBlank()) {
                    text.append('[').append(original.timestamp()).append("] ");
                }
                text.append(original.speaker()).append(": ").append(summary).append('\n');
            }
            return text.toString().strip();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("逐条摘要校验失败", e);
        }
    }
}
