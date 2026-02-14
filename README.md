# CV Summarizer (Local, GPU-Ready, Full-Stack AI)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4-green)
![Docker](https://img.shields.io/badge/Docker-Enabled-2496ED)
![Python](https://img.shields.io/badge/Python-3-blue?logo=python&logoColor=white)
![Angular](https://img.shields.io/badge/Angular-17-red?logo=angular&logoColor=white)
![Node.js](https://img.shields.io/badge/Node.js-20-green?style=flat-square&logo=node.js&logoColor=white)


## Overview
**CV Summarizer** is a full-stack HR screening application that atomatically:
- extract text from uploaded CV PDFs
- generates a professional candidate summary
- answers recruiter questions about the CV
- runs locally with Hugging Face models
- supports GPU acceleration
- streams real-time processing progress


## Current flow

1. HR user uploads a PDF and enters one or more questions.
2. Frontend submits a job via `multipart/form-data` to backend:
   - `file` (PDF)
   - `questions` (multi-value)
   - `useMock` (`true`/`false`)
3. Frontend opens SSE stream for that job and receives live progress.
4. Backend extracts text from PDF.
5. If `useMock=true`, backend returns mock answers.
6. If `useMock=false`, backend runs `backend/python/gpu_infer.py`, which performs actual Hugging Face generation.
7. Frontend shows progress, then final summary + Q&A answers with confidence and citations.

## Project structure

- `backend`: Java Spring Boot API.
- `frontend`: Angular app.
- `backend/python/gpu_infer.py`: Hugging Face local inference script.

## Backend run

Requirements:

- Java 21+
- Maven 3.9+
- Python 3.10+
- Python packages in `backend/python/requirements.txt`

Commands:

```powershell
cd backend
pip install -r python/requirements.txt
mvn spring-boot:run
```

Backend URL: `http://localhost:8080`

### API endpoint

- `POST /api/cv/summarize`
- `Content-Type: multipart/form-data`

Form fields:

- `file`: PDF file
- `questions`: repeat this field for each question
- `useMock`: boolean (`true` or `false`)

### Async + progress endpoints (used by frontend)

- `POST /api/cv/jobs` -> returns `{ "jobId": "..." }`
- `GET /api/cv/jobs/{jobId}/stream` -> `text/event-stream`
  - `progress` event: `{ jobId, status, progress, message }`
  - `result` event: final summarize response
  - `failed` event: `{ error }`

## Frontend run

Requirements:

- Node.js 20+
- npm 10+

Commands:

```powershell
cd frontend
npm install
npm start
```

Frontend URL: `http://localhost:4200`

## Docker one-command local start

Requirements:

- Docker Desktop (or Docker Engine + Compose plugin)

Run from project root:

```powershell
docker compose up --build
```

URLs:

- Frontend: `http://localhost:4200`
- Backend API: `http://localhost:8080`

Stop:

```powershell
docker compose down
```

To enable GPU pass-through for backend in Docker:

```powershell
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up --build
```

## Hugging Face runtime settings

The Python script reads these env vars:

- `HF_MODEL_ID` (default: `TinyLlama/TinyLlama-1.1B-Chat-v1.0`)
- `HF_MAX_INPUT_TOKENS` (default: `2048`)
- `HF_MAX_NEW_TOKENS` (default: `180`)
- `HF_MAX_CV_CHARS` (default: `10000`)

### Where models are downloaded

- Docker run (this project): downloaded models are persisted on your host in `models/hf-cache` (mounted to `/models/hf-cache` inside container).
- Non-Docker local run:
  - Windows: `%USERPROFILE%\\.cache\\huggingface\\hub`
  - Linux/macOS: `~/.cache/huggingface/hub`

Quick check (Docker setup):

```powershell
Get-ChildItem .\models\hf-cache\hub
```

### How to change model

1. Hub model ID (automatic download):
   - Edit `HF_MODEL_ID` in `docker-compose.yml` (backend env).
   - Restart:

```powershell
docker compose up --build
```

2. Local manual model folder (no auto fetch from Hub path):
   - Put model files under `models/local/<your-model-folder>`.
   - Set `HF_MODEL_ID` to `/models/local/<your-model-folder>` in `docker-compose.yml`.
   - Restart compose.

Example:

```yaml
HF_MODEL_ID: /models/local/llama-3-8b-instruct
```

Optional: download to local-model folder using backend container tools:

```powershell
docker compose run --rm backend python3 -m huggingface_hub download `
  TinyLlama/TinyLlama-1.1B-Chat-v1.0 `
  --local-dir /models/local/tinyllama-1.1b-chat
```

Response JSON shape:

```json
{
  "mockMode": false,
  "summary": "string",
  "answers": [
    {
      "question": "string",
      "answer": "string",
      "confidence": 0.0,
      "citations": ["text snippet from CV"]
    }
  ],
  "modelInfo": "your-model-name"
}
```

Optional backend config in `backend/src/main/resources/application.yml`:

- `cvsum.python.executable`
- `cvsum.python.script-path`
- `cvsum.python.timeout-seconds`
