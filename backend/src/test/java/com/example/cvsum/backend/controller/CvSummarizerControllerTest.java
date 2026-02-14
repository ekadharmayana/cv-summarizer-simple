package com.example.cvsum.backend.controller;

import com.example.cvsum.backend.model.SummarizeResponse;
import com.example.cvsum.backend.service.CvProcessingJobService;
import com.example.cvsum.backend.service.MockCvSummarizerService;
import com.example.cvsum.backend.service.RealGpuCvSummarizerService;
import com.example.cvsum.backend.util.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CvSummarizerController.class)
class CvSummarizerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MockCvSummarizerService mockService;

    @MockBean
    private RealGpuCvSummarizerService realService;

    @MockBean
    private PdfTextExtractor pdfTextExtractor;

    @MockBean
    private CvProcessingJobService jobService;

    @Test
    void shouldRouteToMockServiceWhenUseMockIsTrue() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "cv.pdf", MediaType.APPLICATION_PDF_VALUE, "fake".getBytes());
        given(pdfTextExtractor.extractText(file)).willReturn("sample cv");
        given(mockService.summarize(anyString(), anyList()))
                .willReturn(new SummarizeResponse(
                        true,
                        "mock summary",
                        List.of(new SummarizeResponse.AnswerItem(
                                "What is the strongest skill?",
                                "Java",
                                0.91,
                                List.of("5 years Java development")
                        )),
                        "mock-v1"
                ));

        mockMvc.perform(multipart("/api/cv/summarize")
                        .file(file)
                        .param("questions", "What is the strongest skill?")
                        .param("useMock", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mockMode").value(true))
                .andExpect(jsonPath("$.summary").value("mock summary"))
                .andExpect(jsonPath("$.modelInfo").value("mock-v1"))
                .andExpect(jsonPath("$.answers[0].confidence").value(0.91))
                .andExpect(jsonPath("$.answers[0].citations[0]").value("5 years Java development"));
    }

    @Test
    void shouldRouteToRealServiceWhenUseMockIsFalse() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "cv.pdf", MediaType.APPLICATION_PDF_VALUE, "fake".getBytes());
        given(pdfTextExtractor.extractText(file)).willReturn("sample cv");
        given(realService.summarize(anyString(), anyList()))
                .willReturn(new SummarizeResponse(
                        false,
                        "real summary",
                        List.of(new SummarizeResponse.AnswerItem(
                                "How many years of experience?",
                                "6 years",
                                0.84,
                                List.of("Experience: 2019-2025")
                        )),
                        "gpu-v1"
                ));

        mockMvc.perform(multipart("/api/cv/summarize")
                        .file(file)
                        .param("questions", "How many years of experience?")
                        .param("useMock", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mockMode").value(false))
                .andExpect(jsonPath("$.summary").value("real summary"))
                .andExpect(jsonPath("$.modelInfo").value("gpu-v1"))
                .andExpect(jsonPath("$.answers[0].confidence").value(0.84))
                .andExpect(jsonPath("$.answers[0].citations[0]").value("Experience: 2019-2025"));
    }

    @Test
    void shouldCreateAsyncJob() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "cv.pdf", MediaType.APPLICATION_PDF_VALUE, "fake".getBytes());
        given(jobService.submitJob(any(byte[].class), anyList(), eq(true))).willReturn("job-123");

        mockMvc.perform(multipart("/api/cv/jobs")
                        .file(file)
                        .param("questions", "What are top skills?")
                        .param("useMock", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-123"));
    }
}
