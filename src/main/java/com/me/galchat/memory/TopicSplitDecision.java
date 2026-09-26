package com.me.galchat.memory;

import tools.jackson.databind.json.JsonMapper;

/**
 * 字符长度仅计算当前话题；模型只负责语义评分，不接收容量压力。
 * 长度从硬上限的 20% 到 80% 线性增加压力；语义权重 75%、长度权重 25%，阈值 0.75。
 * 语义评分超过 90 时直接切换，不受短话题的长度压力限制。
 */
public record TopicSplitDecision(boolean split, String reason, double pressure, double weightedScore) {
    public static TopicSplitDecision evaluate(int characters, int hardLimit, Integer score) {
        double pressure = Math.clamp((characters - hardLimit * 0.2) / (hardLimit * 0.6), 0, 1);
        double weighted = score == null ? 0 : 0.75 * score / 100.0 + 0.25 * pressure;
        if (characters >= hardLimit) {
            return new TopicSplitDecision(true, "capacity", pressure, weighted);
        }
        boolean split = score != null && (score > 90 || weighted >= 0.75);
        return new TopicSplitDecision(split, split ? "semantic" : "continue", pressure, weighted);
    }

    public static int parseScore(String response) {
        try {
            var score = JsonMapper.builder().build().readTree(response).get("score");
            if (score == null || !score.isIntegralNumber() || !score.canConvertToInt()
                    || score.intValue() < 0 || score.intValue() > 100) {
                throw new IllegalArgumentException("话题评分必须为 0–100 的整数");
            }
            return score.intValue();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("话题评分格式无效", e);
        }
    }
}
