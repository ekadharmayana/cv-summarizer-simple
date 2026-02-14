package com.example.cvsum.backend.model;

import java.util.List;

public record SummarizeResponse(
        boolean mockMode,
        String summary,
        List<AnswerItem> answers,
        String modelInfo
) {
    public record AnswerItem(
            String question,
            String answer,
            double confidence,
            List<String> citations
    ) {
    }
}
