package com.example.cvsum.backend.controller;

import com.example.cvsum.backend.model.JobCreatedResponse;
import com.example.cvsum.backend.model.SummarizeResponse;
import com.example.cvsum.backend.service.CvProcessingJobService;
import com.example.cvsum.backend.service.MockCvSummarizerService;
import com.example.cvsum.backend.service.RealGpuCvSummarizerService;
import com.example.cvsum.backend.util.PdfTextExtractor;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/cv")
public class CvSummarizerController {

    private final MockCvSummarizerService mockService;
    private final RealGpuCvSummarizerService realService;
    private final PdfTextExtractor pdfTextExtractor;
    private final CvProcessingJobService jobService;

    public CvSummarizerController(
            MockCvSummarizerService mockService,
            RealGpuCvSummarizerService realService,
            PdfTextExtractor pdfTextExtractor,
            CvProcessingJobService jobService
    ) {
        this.mockService = mockService;
        this.realService = realService;
        this.pdfTextExtractor = pdfTextExtractor;
        this.jobService = jobService;
    }

    @PostMapping(path = "/summarize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SummarizeResponse> summarizeCv(
            @RequestParam("file") @NotNull MultipartFile file,
            @RequestParam("questions") List<String> questions,
            @RequestParam(name = "useMock", defaultValue = "true") boolean useMock
    ) {
        List<String> normalizedQuestions = validateAndNormalizeQuestions(questions);
        validateFile(file);
        String cvText = pdfTextExtractor.extractText(file);

        SummarizeResponse response = useMock
                ? mockService.summarize(cvText, normalizedQuestions)
                : realService.summarize(cvText, normalizedQuestions);

        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobCreatedResponse> submitJob(
            @RequestParam("file") @NotNull MultipartFile file,
            @RequestParam("questions") List<String> questions,
            @RequestParam(name = "useMock", defaultValue = "true") boolean useMock
    ) {
        List<String> normalizedQuestions = validateAndNormalizeQuestions(questions);
        validateFile(file);
        try {
            String jobId = jobService.submitJob(file.getBytes(), normalizedQuestions, useMock);
            return ResponseEntity.accepted().body(new JobCreatedResponse(jobId));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file.", e);
        }
    }

    @GetMapping(path = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJob(@PathVariable String jobId) {
        return jobService.openProgressStream(jobId);
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is required.");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files are supported.");
        }
    }

    private List<String> validateAndNormalizeQuestions(List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one question is required.");
        }

        List<String> normalized = questions.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();

        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Questions cannot be blank.");
        }

        return normalized;
    }
}
