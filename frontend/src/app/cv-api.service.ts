import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { JobCreatedResponse, SummarizeResponse } from './models';
import { environment } from '../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class CvApiService {
  private readonly summarizeEndpoint = `${environment.apiBaseUrl}/api/cv/summarize`;
  private readonly jobsEndpoint = `${environment.apiBaseUrl}/api/cv/jobs`;

  constructor(private readonly http: HttpClient) {}

  summarizeCv(file: File, questions: string[], useMock: boolean): Observable<SummarizeResponse> {
    const formData = this.buildFormData(file, questions, useMock);
    return this.http.post<SummarizeResponse>(this.summarizeEndpoint, formData);
  }

  submitJob(file: File, questions: string[], useMock: boolean): Observable<JobCreatedResponse> {
    const formData = this.buildFormData(file, questions, useMock);
    return this.http.post<JobCreatedResponse>(this.jobsEndpoint, formData);
  }

  createJobStream(jobId: string): EventSource {
    return new EventSource(`${this.jobsEndpoint}/${jobId}/stream`);
  }

  private buildFormData(file: File, questions: string[], useMock: boolean): FormData {
    const formData = new FormData();
    formData.append('file', file);
    questions.forEach((question) => formData.append('questions', question));
    formData.append('useMock', String(useMock));
    return formData;
  }
}
