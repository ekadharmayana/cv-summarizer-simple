package com.example.cvsum.backend.service;

import com.example.cvsum.backend.model.SummarizeResponse;
import com.example.cvsum.backend.util.PythonBridgeClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RealGpuCvSummarizerService implements CvSummarizerService {

    private final PythonBridgeClient pythonBridgeClient;

    public RealGpuCvSummarizerService(PythonBridgeClient pythonBridgeClient) {
        this.pythonBridgeClient = pythonBridgeClient;
    }

    @Override
    public SummarizeResponse summarize(String cvText, List<String> questions) {
        return summarize(cvText, questions, ProgressListener.NO_OP);
    }

    @Override
    public SummarizeResponse summarize(String cvText, List<String> questions, ProgressListener progressListener) {
        return pythonBridgeClient.runInference(cvText, questions, progressListener);
    }
}
