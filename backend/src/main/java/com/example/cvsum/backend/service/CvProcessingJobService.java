package com.example.cvsum.backend.service;

import com.example.cvsum.backend.model.JobProgressEvent;
import com.example.cvsum.backend.model.SummarizeResponse;
import com.example.cvsum.backend.util.PdfTextExtractor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class CvProcessingJobService {

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final PdfTextExtractor pdfTextExtractor;
    private final MockCvSummarizerService mockService;
    private final RealGpuCvSummarizerService realService;

    public CvProcessingJobService(
            PdfTextExtractor pdfTextExtractor,
            MockCvSummarizerService mockService,
            RealGpuCvSummarizerService realService
    ) {
        this.pdfTextExtractor = pdfTextExtractor;
        this.mockService = mockService;
        this.realService = realService;
    }

    public String submitJob(byte[] pdfBytes, List<String> questions, boolean useMock) {
        String jobId = UUID.randomUUID().toString();
        JobState state = new JobState(jobId);
        jobs.put(jobId, state);
        updateProgress(state, JobStatus.QUEUED, 0, "Job accepted.");

        executor.submit(() -> runJob(state, pdfBytes, questions, useMock));
        return jobId;
    }

    public SseEmitter openProgressStream(String jobId) {
        JobState state = jobs.get(jobId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found.");
        }

        SseEmitter emitter = new SseEmitter(0L);
        state.emitters.add(emitter);
        emitter.onCompletion(() -> state.emitters.remove(emitter));
        emitter.onTimeout(() -> {
            state.emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError((ex) -> state.emitters.remove(emitter));

        sendProgressEvent(emitter, state);
        if (state.status == JobStatus.COMPLETED && state.result != null) {
            sendResultEvent(emitter, state.result);
            emitter.complete();
        } else if (state.status == JobStatus.FAILED) {
            sendFailureEvent(emitter, state.errorMessage);
            emitter.complete();
        }

        return emitter;
    }

    private void runJob(JobState state, byte[] pdfBytes, List<String> questions, boolean useMock) {
        try {
            updateProgress(state, JobStatus.RUNNING, 5, "Extracting text from PDF.");
            String cvText = pdfTextExtractor.extractText(pdfBytes);
            updateProgress(state, JobStatus.RUNNING, 20, "PDF extracted. Starting inference.");

            ProgressListener listener = (progress, message) -> {
                int bounded = Math.max(0, Math.min(100, progress));
                int mapped = 20 + (int) Math.round(bounded * 0.75);
                updateProgress(state, JobStatus.RUNNING, mapped, message);
            };

            SummarizeResponse result = useMock
                    ? mockService.summarize(cvText, questions, listener)
                    : realService.summarize(cvText, questions, listener);

            state.result = result;
            updateProgress(state, JobStatus.COMPLETED, 100, "Processing completed.");
            broadcastResult(state, result);
        } catch (Exception e) {
            String error = e.getMessage() == null ? "Processing failed." : e.getMessage();
            state.errorMessage = error;
            updateProgress(state, JobStatus.FAILED, 100, error);
            broadcastFailure(state, error);
        }
    }

    private void updateProgress(JobState state, JobStatus status, int progress, String message) {
        state.status = status;
        state.progress = Math.max(0, Math.min(100, progress));
        state.message = message;
        broadcastProgress(state);
    }

    private void broadcastProgress(JobState state) {
        for (SseEmitter emitter : state.emitters) {
            sendProgressEvent(emitter, state);
        }
    }

    private void broadcastResult(JobState state, SummarizeResponse result) {
        for (SseEmitter emitter : state.emitters) {
            sendResultEvent(emitter, result);
            emitter.complete();
        }
        state.emitters.clear();
    }

    private void broadcastFailure(JobState state, String error) {
        for (SseEmitter emitter : state.emitters) {
            sendFailureEvent(emitter, error);
            emitter.complete();
        }
        state.emitters.clear();
    }

    private void sendProgressEvent(SseEmitter emitter, JobState state) {
        sendEvent(emitter, "progress", new JobProgressEvent(
                state.jobId,
                state.status.name(),
                state.progress,
                state.message
        ));
    }

    private void sendResultEvent(SseEmitter emitter, SummarizeResponse result) {
        sendEvent(emitter, "result", result);
    }

    private void sendFailureEvent(SseEmitter emitter, String error) {
        sendEvent(emitter, "failed", Map.of("error", error));
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    @PreDestroy
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    private enum JobStatus {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    private static final class JobState {
        private final String jobId;
        private volatile JobStatus status = JobStatus.QUEUED;
        private volatile int progress = 0;
        private volatile String message = "Job accepted.";
        private volatile SummarizeResponse result;
        private volatile String errorMessage = "Processing failed.";
        private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

        private JobState(String jobId) {
            this.jobId = jobId;
        }
    }
}
