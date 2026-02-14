import { CommonModule } from '@angular/common';
import { Component, OnDestroy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CvApiService } from './cv-api.service';
import { JobProgressEvent, SummarizeResponse } from './models';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnDestroy {
  selectedFile: File | null = null;
  questionText = '';
  useMock = true;
  isLoading = false;
  errorMessage = '';
  progress = 0;
  progressMessage = '';
  currentJobId = '';
  result: SummarizeResponse | null = null;
  private eventSource: EventSource | null = null;

  constructor(private readonly cvApiService: CvApiService) {}

  ngOnDestroy(): void {
    this.closeStream();
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile = input.files && input.files.length > 0 ? input.files[0] : null;
  }

  submit(): void {
    this.errorMessage = '';
    this.result = null;
    this.progress = 0;
    this.progressMessage = '';
    this.currentJobId = '';

    if (!this.selectedFile) {
      this.errorMessage = 'Please choose a PDF file.';
      return;
    }

    const questions = this.questionText
      .split('\n')
      .map((q) => q.trim())
      .filter((q) => q.length > 0);

    if (questions.length === 0) {
      this.errorMessage = 'Please enter at least one question.';
      return;
    }

    this.closeStream();
    this.isLoading = true;
    this.progressMessage = 'Submitting job...';
    this.cvApiService.submitJob(this.selectedFile, questions, this.useMock).subscribe({
      next: (job) => {
        this.currentJobId = job.jobId;
        this.openStream(job.jobId);
      },
      error: (err) => {
        this.errorMessage = err?.error?.message || 'Failed to process CV.';
        this.isLoading = false;
      }
    });
  }

  private openStream(jobId: string): void {
    this.eventSource = this.cvApiService.createJobStream(jobId);

    this.eventSource.addEventListener('progress', (event) => {
      const data = JSON.parse((event as MessageEvent<string>).data) as JobProgressEvent;
      this.progress = data.progress;
      this.progressMessage = data.message || 'Running inference...';
    });

    this.eventSource.addEventListener('result', (event) => {
      const data = JSON.parse((event as MessageEvent<string>).data) as SummarizeResponse;
      this.result = data;
      this.progress = 100;
      this.progressMessage = 'Completed.';
      this.isLoading = false;
      this.closeStream();
    });

    this.eventSource.addEventListener('failed', (event) => {
      const data = JSON.parse((event as MessageEvent<string>).data) as { error?: string };
      this.errorMessage = data.error || 'Inference failed.';
      this.isLoading = false;
      this.closeStream();
    });

    this.eventSource.onerror = () => {
      if (this.isLoading) {
        this.errorMessage = 'Progress stream disconnected.';
        this.isLoading = false;
      }
      this.closeStream();
    };
  }

  private closeStream(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }
}
