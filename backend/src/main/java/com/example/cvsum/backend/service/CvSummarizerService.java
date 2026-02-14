package com.example.cvsum.backend.service;

import com.example.cvsum.backend.model.SummarizeResponse;

import java.util.List;

public interface CvSummarizerService {
    SummarizeResponse summarize(String cvText, List<String> questions);

    default SummarizeResponse summarize(String cvText, List<String> questions, ProgressListener progressListener) {
        return summarize(cvText, questions);
    }
}
