from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional

from groq import Groq

from build_rag_artifacts import create_embedding_text


DEFAULT_OPENAI_MODEL = "gpt-5.2"
DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
OPENAI_RESPONSES_API = "https://api.openai.com/v1/responses"
GROQ_CHAT_COMPLETIONS_API = "https://api.groq.com/openai/v1/chat/completions"
SYSTEM_INSTRUCTIONS = (
    "You summarize code and software-architecture chunks for retrieval. "
    "Return only the requested summary content. "
    "Do not add preambles, labels, markdown, bullet lists, explanations, or wrapper text outside the requested field. "
    "Write a compact, high-signal summary that explains purpose first, behavior second, and important dependencies, "
    "framework roles, endpoint meaning, or risks only when materially useful. "
    "Keep it concise, concrete, and faithful to the input."
)
GROQ_JSON_ONLY_INSTRUCTIONS = (
    SYSTEM_INSTRUCTIONS
    + ' Respond with valid JSON only, in exactly this shape: {"summary": "..."}.'
)


class ApiCallError(RuntimeError):
    def __init__(self, provider: str, status_code: int, detail: str) -> None:
        self.provider = provider
        self.status_code = status_code
        self.detail = detail
        message = f"{provider} API error {status_code}: {detail}"
        if provider == "groq" and status_code == 403:
            message += (
                "\nHint: this usually means the Groq API key is invalid, expired, restricted, "
                "or the selected model/account is not allowed for this request."
            )
        if provider == "openai" and status_code in (401, 403):
            message += "\nHint: check OPENAI_API_KEY, project access, and model permissions."
        super().__init__(message)


def load_dotenv(dotenv_path: Path) -> None:
    if not dotenv_path.exists():
        return
    for raw_line in dotenv_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def compact_metadata(metadata: Dict[str, Any]) -> Dict[str, Any]:
    allowed = [
        "project_name",
        "qualified_name",
        "class_name",
        "method_name",
        "signature",
        "package",
        "layer",
        "type",
        "bucket",
        "method_role",
        "return_type",
        "endpoint",
        "resolved_endpoint",
        "http_methods",
        "dependencies",
        "internal_calls",
        "external_calls",
        "framework_calls",
        "constants_used",
        "key",
        "description",
        "category",
        "sensitive",
        "environments",
        "used_by",
        "name",
        "configs",
        "internal",
        "collaborators",
        "internal_dependencies",
        "external_dependencies",
        "method_refs",
        "annotations",
        "fields",
    ]
    result: Dict[str, Any] = {}
    for key in allowed:
        value = metadata.get(key)
        if value in (None, [], {}, ""):
            continue
        result[key] = value
    return result


def build_user_prompt(chunk: Dict[str, Any]) -> str:
    metadata = compact_metadata(chunk.get("metadata") or {})
    content = chunk.get("content", "")
    return (
        "Summarize this repository chunk for retrieval and debugging assistance.\n\n"
        f"Chunk type: {chunk.get('chunk_type')}\n"
        f"Title: {chunk.get('title')}\n"
        f"Source file: {chunk.get('source_file')}\n"
        f"Metadata: {json.dumps(metadata, ensure_ascii=False)}\n\n"
        "Chunk content:\n"
        f"{content}\n\n"
        "Write only the summary content. Focus on why it exists, what it does, and the most useful technical context."
    )


def extract_responses_output_text(response_json: Dict[str, Any]) -> str:
    texts: List[str] = []
    for output_item in response_json.get("output", []) or []:
        for content_item in output_item.get("content", []) or []:
            text_value = content_item.get("text")
            if isinstance(text_value, str) and text_value.strip():
                texts.append(text_value)
    return "\n".join(texts).strip()


def parse_summary_json(text: str) -> str:
    if not text:
        raise RuntimeError("Model returned no text")
    parsed = json.loads(text)
    summary = (parsed.get("summary") or "").strip()
    if not summary:
        raise RuntimeError("Model returned an empty summary")
    return summary


def post_json(url: str, payload: Dict[str, Any], api_key: str, timeout_seconds: int, provider: str) -> Dict[str, Any]:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise ApiCallError(provider, exc.code, detail) from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Network error while calling {provider} API: {exc}") from exc
    return json.loads(body)


class BaseSummarizer:
    provider_name = "base"

    def summarize(self, chunk: Dict[str, Any]) -> str:
        raise NotImplementedError


class OpenAIResponsesSummarizer(BaseSummarizer):
    provider_name = "openai"

    def __init__(self, api_key: str, model: str, api_base: str = OPENAI_RESPONSES_API, timeout_seconds: int = 120) -> None:
        self.api_key = api_key
        self.model = model
        self.api_base = api_base
        self.timeout_seconds = timeout_seconds

    def build_request(self, chunk: Dict[str, Any]) -> Dict[str, Any]:
        schema = {
            "type": "object",
            "properties": {
                "summary": {
                    "type": "string",
                    "description": "A concise, high-signal semantic summary of the chunk.",
                }
            },
            "required": ["summary"],
            "additionalProperties": False,
        }
        return {
            "model": self.model,
            "instructions": SYSTEM_INSTRUCTIONS,
            "input": build_user_prompt(chunk),
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "chunk_summary",
                    "schema": schema,
                    "strict": True,
                }
            },
        }

    def summarize(self, chunk: Dict[str, Any]) -> str:
        response_json = post_json(
            self.api_base,
            self.build_request(chunk),
            self.api_key,
            self.timeout_seconds,
            self.provider_name,
        )
        return parse_summary_json(extract_responses_output_text(response_json))


class GroqChatSummarizer(BaseSummarizer):
    provider_name = "groq"

    def __init__(self, api_key: str, model: str, api_base: str = GROQ_CHAT_COMPLETIONS_API, timeout_seconds: int = 120) -> None:
        self.api_key = api_key
        self.model = model
        self.api_base = api_base
        self.timeout_seconds = timeout_seconds
        self.client = Groq(api_key=api_key)

    def summarize(self, chunk: Dict[str, Any]) -> str:
        try:
            completion = self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": GROQ_JSON_ONLY_INSTRUCTIONS},
                    {"role": "user", "content": build_user_prompt(chunk)},
                ],
                response_format={"type": "json_object"},
                temperature=0.2,
                stream=False,
            )
        except Exception as exc:
            raise RuntimeError(f"groq SDK error: {exc}") from exc
        content = completion.choices[0].message.content if completion.choices else ""
        return parse_summary_json(content or "")


PROVIDER_CONFIG = {
    "openai": {"api_key_env": "OPENAI_API_KEY", "default_model": DEFAULT_OPENAI_MODEL},
    "groq": {"api_key_env": "GROQ_API_KEY", "default_model": DEFAULT_GROQ_MODEL},
}


def resolve_provider(provider: str) -> str:
    if provider != "auto":
        return provider
    preferred = os.getenv("SUMMARY_PROVIDER", "auto").strip().lower()
    if preferred in ("openai", "groq"):
        return preferred
    if os.getenv("OPENAI_API_KEY"):
        return "openai"
    if os.getenv("GROQ_API_KEY"):
        return "groq"
    return "openai"


def create_summarizer(provider: str, model: Optional[str], api_base: Optional[str]) -> BaseSummarizer:
    provider = resolve_provider(provider)
    config = PROVIDER_CONFIG[provider]
    api_key = os.getenv(config["api_key_env"])
    if not api_key:
        raise RuntimeError(f"{config['api_key_env']} is missing. Set it in the environment or .env before running this script.")

    if provider == "openai":
        chosen_model = model or os.getenv("OPENAI_MODEL") or config["default_model"]
        return OpenAIResponsesSummarizer(api_key=api_key, model=chosen_model, api_base=api_base or OPENAI_RESPONSES_API)
    if provider == "groq":
        chosen_model = model or os.getenv("GROQ_MODEL") or config["default_model"]
        return GroqChatSummarizer(api_key=api_key, model=chosen_model, api_base=api_base or GROQ_CHAT_COMPLETIONS_API)
    raise RuntimeError(f"Unsupported provider: {provider}")


def rebuild_chunk(chunk: Dict[str, Any], summary: str) -> Dict[str, Any]:
    updated = dict(chunk)
    updated["summary"] = summary
    updated["summary_status"] = "generated"
    updated["embedding_text"] = create_embedding_text(
        updated.get("chunk_type", ""),
        updated.get("title", ""),
        updated.get("metadata") or {},
        updated.get("content", ""),
        summary,
    )
    return updated


def summarize_chunks(
    chunks_payload: Dict[str, Any],
    summarizer: BaseSummarizer,
    *,
    limit: Optional[int],
    force: bool,
    sleep_seconds: float,
    reused_summaries: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    reused_summaries = reused_summaries or {}
    updated_chunks: List[Dict[str, Any]] = []
    processed = 0
    for chunk in chunks_payload.get("chunks", []):
        chunk_id = chunk.get("id")
        reused_summary = (reused_summaries.get(chunk_id) or "").strip() if chunk_id else ""
        if reused_summary and not force:
            updated_chunks.append(rebuild_chunk(chunk, reused_summary))
            continue
        has_summary = bool((chunk.get("summary") or "").strip())
        if has_summary and not force:
            updated_chunks.append(rebuild_chunk(chunk, chunk.get("summary") or ""))
            continue
        if limit is not None and processed >= limit:
            updated_chunks.append(chunk)
            continue

        summary = summarizer.summarize(chunk)
        updated_chunks.append(rebuild_chunk(chunk, summary))
        processed += 1
        if sleep_seconds > 0:
            time.sleep(sleep_seconds)

    updated_payload = dict(chunks_payload)
    updated_payload["chunks"] = updated_chunks
    stats = dict(updated_payload.get("stats") or {})
    stats["summarized_chunks"] = sum(1 for chunk in updated_chunks if (chunk.get("summary") or "").strip())
    updated_payload["stats"] = stats
    return updated_payload


