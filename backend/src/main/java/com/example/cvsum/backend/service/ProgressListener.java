package com.example.cvsum.backend.service;

@FunctionalInterface
public interface ProgressListener {
    ProgressListener NO_OP = (progress, message) -> {
    };

    void onProgress(int progress, String message);
}
