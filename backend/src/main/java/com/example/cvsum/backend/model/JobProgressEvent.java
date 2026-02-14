package com.example.cvsum.backend.model;

public record JobProgressEvent(
        String jobId,
        String status,
        int progress,
        String message
) {
}
