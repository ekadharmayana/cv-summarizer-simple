import json
import os
import re
import sys
from typing import List, Tuple

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

TOKEN_RE = re.compile(r"[a-zA-Z0-9]+")
MAX_CITATIONS = 2


def emit_progress(progress: int, message: str) -> None:
    payload = {"progress": max(0, min(100, progress)), "message": message}
    sys.stderr.write(f"PROGRESS:{json.dumps(payload)}\n")
    sys.stderr.flush()


def tokenize(text: str) -> set[str]:
    return {token.lower() for token in TOKEN_RE.findall(text or "")}


def score_overlap(question: str, snippet: str) -> float:
    q_tokens = tokenize(question)
    if not q_tokens:
        return 0.0
    s_tokens = tokenize(snippet)
    return len(q_tokens.intersection(s_tokens)) / len(q_tokens)


def split_snippets(cv_text: str) -> List[str]:
    if not cv_text:
        return []

    parts = re.split(r"(?<=[.!?])\s+|\r?\n", cv_text)
    snippets = []
    for part in parts:
        cleaned = " ".join(part.split())
        if len(cleaned) > 20:
            snippets.append(cleaned)

    if not snippets:
        fallback = " ".join(cv_text.split())
        if fallback:
            snippets.append(fallback)
    return snippets


def choose_citations(question: str, snippets: List[str]) -> List[str]:
    if not snippets:
        return ["No text could be extracted from the CV."]

    ranked = sorted(snippets, key=lambda s: score_overlap(question, s), reverse=True)
    top = ranked[:MAX_CITATIONS]
    if score_overlap(question, top[0]) == 0:
        top = [snippets[0]]
    return [item[:220] + "..." if len(item) > 220 else item for item in top]


def build_prompt(cv_text: str, question: str) -> str:
    system = (
        "You are an HR assistant. Answer the question using only facts from the CV text. "
        "If uncertain, say so briefly."
    )
    user = (
        f"CV TEXT:\n{cv_text}\n\n"
        f"QUESTION:\n{question}\n\n"
        "Return only the short answer text."
    )
    return f"{system}\n\n{user}\n\nANSWER:"


def render_input_text(tokenizer, prompt: str) -> str:
    if hasattr(tokenizer, "apply_chat_template"):
        messages = [
            {"role": "system", "content": "You are an HR assistant."},
            {"role": "user", "content": prompt},
        ]
        return tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    return prompt


def estimate_confidence(question: str, citations: List[str], token_confidence: float) -> float:
    overlap = 0.0
    for citation in citations:
        overlap = max(overlap, score_overlap(question, citation))

    blended = (0.45 * overlap) + (0.55 * token_confidence)
    confidence = 0.2 + (0.78 * blended)
    return max(0.1, min(0.99, confidence))


def compute_token_confidence(scores, generated_ids) -> float:
    if not scores:
        return 0.5

    probs = []
    for step_scores, token_id in zip(scores, generated_ids):
        distribution = torch.softmax(step_scores[0], dim=-1)
        probs.append(distribution[int(token_id)].item())

    if not probs:
        return 0.5
    return sum(probs) / len(probs)


def generate_answer(
    model,
    tokenizer,
    device: str,
    cv_text: str,
    question: str,
    max_input_tokens: int,
    max_new_tokens: int,
) -> Tuple[str, float]:
    prompt = build_prompt(cv_text, question)
    input_text = render_input_text(tokenizer, prompt)

    encoded = tokenizer(
        input_text,
        return_tensors="pt",
        truncation=True,
        max_length=max_input_tokens,
    )
    if device == "cuda":
        encoded = {k: v.to(model.device) for k, v in encoded.items()}

    with torch.no_grad():
        output = model.generate(
            **encoded,
            max_new_tokens=max_new_tokens,
            do_sample=False,
            temperature=0.0,
            pad_token_id=tokenizer.pad_token_id,
            eos_token_id=tokenizer.eos_token_id,
            return_dict_in_generate=True,
            output_scores=True,
        )

    input_len = encoded["input_ids"].shape[-1]
    generated_ids = output.sequences[0][input_len:]
    answer = tokenizer.decode(generated_ids, skip_special_tokens=True).strip()
    token_confidence = compute_token_confidence(output.scores, generated_ids)

    if not answer:
        answer = "I could not confidently extract this answer from the CV."
        token_confidence = min(token_confidence, 0.35)
    return answer, token_confidence


def main() -> None:
    emit_progress(3, "Reading request payload.")
    raw = sys.stdin.read()
    if not raw:
        raise ValueError("No input payload received.")

    payload = json.loads(raw)
    cv_text = payload.get("cv_text", "")
    questions = payload.get("questions", [])

    model_id = os.getenv("HF_MODEL_ID", "TinyLlama/TinyLlama-1.1B-Chat-v1.0")
    max_input_tokens = int(os.getenv("HF_MAX_INPUT_TOKENS", "2048"))
    max_new_tokens = int(os.getenv("HF_MAX_NEW_TOKENS", "180"))
    max_cv_chars = int(os.getenv("HF_MAX_CV_CHARS", "10000"))
    cv_excerpt = (cv_text or "")[:max_cv_chars]

    device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.float16 if device == "cuda" else torch.float32

    emit_progress(12, f"Loading tokenizer ({model_id}).")
    tokenizer = AutoTokenizer.from_pretrained(model_id, use_fast=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    emit_progress(30, f"Loading model on {device}.")
    model = AutoModelForCausalLM.from_pretrained(
        model_id,
        torch_dtype=dtype,
        device_map="auto" if device == "cuda" else None,
    )
    if device != "cuda":
        model.to(device)
    model.eval()

    emit_progress(45, "Preparing citation snippets.")
    snippets = split_snippets(cv_excerpt)

    answers = []
    total = max(len(questions), 1)
    for idx, question in enumerate(questions, start=1):
        phase_progress = 45 + int((idx - 1) / total * 45)
        emit_progress(phase_progress, f"Generating answer {idx}/{total}.")

        answer, token_conf = generate_answer(
            model=model,
            tokenizer=tokenizer,
            device=device,
            cv_text=cv_excerpt,
            question=question,
            max_input_tokens=max_input_tokens,
            max_new_tokens=max_new_tokens,
        )
        citations = choose_citations(question, snippets)
        confidence = estimate_confidence(question, citations, token_conf)
        answers.append(
            {
                "question": question,
                "answer": answer,
                "confidence": confidence,
                "citations": citations,
            }
        )

    preview = " ".join(cv_excerpt.split())[:350]
    output = {
        "mockMode": False,
        "summary": f"Candidate summary from local Hugging Face model: {preview}",
        "answers": answers,
        "modelInfo": f"{model_id} ({device})",
    }
    emit_progress(100, "Inference complete.")
    print(json.dumps(output))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        sys.stderr.write(f"ERROR:{exc}\n")
        sys.stderr.flush()
        raise
