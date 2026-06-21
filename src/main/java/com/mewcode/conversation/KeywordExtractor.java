package com.mewcode.conversation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Comparator;
import java.util.Map;
import java.util.HashMap;

/**
 * Simple keyword extraction via word frequency.
 * Strips stop-words and punctuation, returns top-N frequent words.
 */
public class KeywordExtractor {

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "can", "shall", "to", "of", "in", "for",
            "on", "with", "at", "by", "from", "as", "into", "through", "during",
            "before", "after", "above", "below", "between", "under", "again",
            "further", "then", "once", "here", "there", "when", "where", "why",
            "how", "all", "both", "each", "few", "more", "most", "other", "some",
            "such", "no", "nor", "not", "only", "own", "same", "so", "than",
            "too", "very", "just", "because", "but", "and", "or", "if", "while",
            "about", "up", "down", "out", "off", "over", "it", "its", "he", "she",
            "they", "them", "their", "we", "us", "our", "you", "your", "me", "my",
            "mine", "him", "his", "her", "I", "am", "this", "that", "these", "those",
            "什么", "这是", "一个", "这个", "那个", "什么", "怎么", "怎么样",
            "为什么", "哪里", "的", "了", "在", "是", "我", "你", "他", "她",
            "它", "们", "有", "和", "就", "都", "也", "对", "与", "被", "把",
            "让", "给", "从", "向", "到", "还", "要", "会", "但", "而", "或",
            "不", "很", "这", "那", "着", "呢", "吗", "吧", "啊", "哦", "嗯",
            "可以", "已经", "因为", "所以", "如果", "虽然", "但是", "并且",
            "或者", "不过", "然后", "没有", "自己", "知道", "觉得", "认为",
            "应该"
    ));

    /**
     * Extract top-N keywords from text by word frequency after stop-word filtering.
     *
     * @param text input text
     * @param topN number of keywords to return
     * @return list of keywords, sorted by frequency descending
     */
    public List<String> extract(String text, int topN) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Tokenize: split on whitespace and punctuation, keep only word-like tokens
        String[] tokens = text.toLowerCase().split("[\\s\\p{Punct}　-〿＀-￯\\-]+");

        Map<String, Integer> freq = new HashMap<>();
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.length() < 2) continue;
            if (STOP_WORDS.contains(trimmed)) continue;
            freq.merge(trimmed, 1, Integer::sum);
        }

        return freq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
