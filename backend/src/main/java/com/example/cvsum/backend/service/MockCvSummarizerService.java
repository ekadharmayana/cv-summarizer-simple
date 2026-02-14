package com.example.cvsum.backend.service;

import com.example.cvsum.backend.model.SummarizeResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MockCvSummarizerService implements CvSummarizerService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9]+");
    private static final int MAX_CITATIONS = 2;

    @Override
    public SummarizeResponse summarize(String cvText, List<String> questions) {
        return summarize(cvText, questions, ProgressListener.NO_OP);
    }

    @Override
    public SummarizeResponse summarize(String cvText, List<String> questions, ProgressListener progressListener) {
        progressListener.onProgress(5, "Preparing mock analysis.");
        String normalized = cvText == null ? "" : cvText.replaceAll("\\s+", " ").trim();
        String shortPreview = normalized.length() > 300 ? normalized.substring(0, 300) + "..." : normalized;

        String summary = shortPreview.isBlank()
                ? "No text was extracted from the uploaded CV."
                : "Candidate profile (mock): " + shortPreview;

        progressListener.onProgress(30, "Selecting supporting citations.");
        List<String> snippets = extractSnippets(cvText);
        List<SummarizeResponse.AnswerItem> answers = new ArrayList<>();
        int totalQuestions = Math.max(questions.size(), 1);
        for (int i = 0; i < questions.size(); i++) {
            String q = questions.get(i);
            List<String> citations = selectCitations(q, snippets, normalized);
            double confidence = estimateConfidence(q, citations);
            String answer = "Mock answer for: \"" + q + "\". "
                    + "Switch off mock mode later to run real local GPU inference.";
            answers.add(new SummarizeResponse.AnswerItem(q, answer, confidence, citations));
            int progress = 40 + (int) Math.round(((i + 1) / (double) totalQuestions) * 50);
            progressListener.onProgress(progress, "Generated mock answer " + (i + 1) + "/" + totalQuestions + ".");
        }

        progressListener.onProgress(100, "Mock inference complete.");
        return new SummarizeResponse(
                true,
                summary,
                answers,
                "mock-rule-engine-v1"
        );
    }

    private List<String> extractSnippets(String cvText) {
        if (!StringUtils.hasText(cvText)) {
            return List.of();
        }

        String[] rawParts = cvText.split("(?<=[.!?])\\s+|\\r?\\n");
        List<String> snippets = new ArrayList<>();
        for (String part : rawParts) {
            String cleaned = part.replaceAll("\\s+", " ").trim();
            if (cleaned.length() > 20) {
                snippets.add(cleaned);
            }
        }

        if (snippets.isEmpty()) {
            String fallback = cvText.replaceAll("\\s+", " ").trim();
            if (StringUtils.hasText(fallback)) {
                snippets.add(fallback);
            }
        }

        return snippets;
    }

    private List<String> selectCitations(String question, List<String> snippets, String normalizedCvText) {
        if (snippets.isEmpty()) {
            return normalizedCvText.isBlank()
                    ? List.of("No text could be extracted from the CV.")
                    : List.of(truncate(normalizedCvText, 220));
        }

        Set<String> questionTokens = tokenize(question);
        List<String> ranked = snippets.stream()
                .sorted(Comparator.comparingDouble((String snippet) -> scoreByTokenOverlap(questionTokens, snippet)).reversed())
                .limit(MAX_CITATIONS)
                .toList();

        double bestScore = ranked.stream()
                .mapToDouble(snippet -> scoreByTokenOverlap(questionTokens, snippet))
                .max()
                .orElse(0.0);

        if (bestScore == 0.0) {
            return List.of(truncate(snippets.get(0), 220));
        }

        List<String> citations = new ArrayList<>();
        for (String snippet : ranked) {
            citations.add(truncate(snippet, 220));
        }
        return citations;
    }

    private double estimateConfidence(String question, List<String> citations) {
        if (citations.isEmpty()) {
            return 0.25;
        }

        Set<String> questionTokens = tokenize(question);
        double bestScore = citations.stream()
                .mapToDouble(citation -> scoreByTokenOverlap(questionTokens, citation))
                .max()
                .orElse(0.0);

        double confidence = 0.35 + (bestScore * 0.55);
        if (citations.size() > 1) {
            confidence += 0.05;
        }

        return Math.max(0.25, Math.min(0.98, confidence));
    }

    private double scoreByTokenOverlap(Set<String> questionTokens, String text) {
        if (questionTokens.isEmpty() || !StringUtils.hasText(text)) {
            return 0.0;
        }

        Set<String> textTokens = tokenize(text);
        long matches = questionTokens.stream().filter(textTokens::contains).count();
        return matches / (double) questionTokens.size();
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (!StringUtils.hasText(text)) {
            return tokens;
        }

        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase());
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
