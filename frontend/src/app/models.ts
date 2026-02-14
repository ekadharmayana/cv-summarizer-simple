export interface AnswerItem {
  question: string;
  answer: string;
  confidence: number;
  citations: string[];
}

export interface SummarizeResponse {
  mockMode: boolean;
  summary: string;
  answers: AnswerItem[];
  modelInfo: string;
}

export interface JobCreatedResponse {
  jobId: string;
}

export interface JobProgressEvent {
  jobId: string;
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  progress: number;
  message: string;
}
