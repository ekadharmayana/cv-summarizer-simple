package com.example.cvsum.backend.util;

import com.example.cvsum.backend.model.SummarizeResponse;
import com.example.cvsum.backend.service.ProgressListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class PythonBridgeClient {

    private final ObjectMapper objectMapper;
    private final String pythonExecutable;
    private final String scriptPath;
    private final int timeoutSeconds;

    public PythonBridgeClient(
            ObjectMapper objectMapper,
            @Value("${cvsum.python.executable:python}") String pythonExecutable,
            @Value("${cvsum.python.script-path:python/gpu_infer.py}") String scriptPath,
            @Value("${cvsum.python.timeout-seconds:60}") int timeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.pythonExecutable = pythonExecutable;
        this.scriptPath = scriptPath;
        this.timeoutSeconds = timeoutSeconds;
    }

    public SummarizeResponse runInference(String cvText, List<String> questions) {
        return runInference(cvText, questions, ProgressListener.NO_OP);
    }

    public SummarizeResponse runInference(String cvText, List<String> questions, ProgressListener progressListener) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("cv_text", cvText);
        payload.put("questions", questions);

        String inputJson;
        try {
            inputJson = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to prepare inference payload.", e);
        }

        ProcessBuilder pb = new ProcessBuilder(pythonExecutable, resolveScriptPath());
        pb.redirectErrorStream(false);

        try {
            Process process = pb.start();
            StringBuilder stderrCapture = new StringBuilder();
            Thread stderrThread = startStderrReader(process.getErrorStream(), stderrCapture, progressListener);

            process.getOutputStream().write(inputJson.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            process.getOutputStream().close();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "Python inference timed out after " + Duration.ofSeconds(timeoutSeconds)
                );
            }

            stderrThread.join(1000);
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = stderrCapture.toString().trim();

            if (process.exitValue() != 0) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Python inference failed: " + stderr
                );
            }
            if (stdout.isBlank()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Python inference returned no output.");
            }

            progressListener.onProgress(100, "Inference response parsed.");
            return parseResponse(stdout, cvText);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Python inference was interrupted.", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to run Python inference.", e);
        }
    }

    private SummarizeResponse parseResponse(String stdout, String cvText) {
        try {
            JsonNode root = objectMapper.readTree(stdout);
            String summary = root.path("summary").asText("");
            String modelInfo = root.path("modelInfo").asText("python-gpu-bridge");
            boolean mockMode = root.path("mockMode").asBoolean(false);

            List<SummarizeResponse.AnswerItem> answers = new ArrayList<>();
            JsonNode answersNode = root.path("answers");
            if (answersNode.isArray()) {
                for (JsonNode item : answersNode) {
                    List<String> citations = new ArrayList<>();
                    JsonNode citationsNode = item.path("citations");
                    if (citationsNode.isArray()) {
                        for (JsonNode citationNode : citationsNode) {
                            String citation = citationNode.asText("");
                            if (StringUtils.hasText(citation)) {
                                citations.add(citation.trim());
                            }
                        }
                    }
                    if (citations.isEmpty()) {
                        citations.add(buildFallbackCitation(cvText));
                    }

                    answers.add(new SummarizeResponse.AnswerItem(
                            item.path("question").asText(""),
                            item.path("answer").asText(""),
                            normalizeConfidence(item.path("confidence").asDouble(0.5)),
                            citations
                    ));
                }
            }

            return new SummarizeResponse(mockMode, summary, answers, modelInfo);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid response from Python inference.", e);
        }
    }

    private double normalizeConfidence(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String buildFallbackCitation(String cvText) {
        if (cvText == null || cvText.isBlank()) {
            return "No text could be extracted from the CV.";
        }

        String normalized = cvText.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 220) + "...";
    }

    private String resolveScriptPath() {
        Path configured = Path.of(scriptPath);
        if (configured.isAbsolute() || Files.exists(configured)) {
            return configured.toString();
        }

        Path fromRepoRoot = Path.of("backend").resolve(scriptPath);
        if (Files.exists(fromRepoRoot)) {
            return fromRepoRoot.toString();
        }

        return configured.toString();
    }

    private Thread startStderrReader(InputStream errorStream, StringBuilder stderrCapture, ProgressListener progressListener) {
        Thread stderrThread = new Thread(() -> readStderr(errorStream, stderrCapture, progressListener));
        stderrThread.setDaemon(true);
        stderrThread.start();
        return stderrThread;
    }

    private void readStderr(InputStream errorStream, StringBuilder stderrCapture, ProgressListener progressListener) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (handleProgressLine(line, progressListener)) {
                    continue;
                }
                if (StringUtils.hasText(line)) {
                    stderrCapture.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // Ignore stderr read errors; process exit code will determine failure.
        }
    }

    private boolean handleProgressLine(String line, ProgressListener progressListener) {
        if (!line.startsWith("PROGRESS:")) {
            return false;
        }

        String payload = line.substring("PROGRESS:".length()).trim();
        try {
            JsonNode progress = objectMapper.readTree(payload);
            int percentage = Math.max(0, Math.min(100, progress.path("progress").asInt(0)));
            String message = progress.path("message").asText("Running inference.");
            progressListener.onProgress(percentage, message);
        } catch (IOException ignored) {
            // Ignore malformed progress message and continue inference.
        }
        return true;
    }
}
