from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from typing import Any, Dict, Iterable, List, Optional, Tuple


DEFAULT_OPENAI_MODEL = "text-embedding-3-small"
DEFAULT_OPENAI_API_BASE = "https://api.openai.com/v1/embeddings"
DEFAULT_JINA_MODEL = "jina-embeddings-v3"
DEFAULT_JINA_API_BASE = "https://api.jina.ai/v1/embeddings"


def chunked(values: List[str], size: int) -> Iterable[List[str]]:
    for start in range(0, len(values), size):
        yield values[start : start + size]


class BaseEmbedder:
    provider_name = "base"
    model_name = ""

    def embed_texts(self, texts: List[str]) -> List[List[float]]:
        raise NotImplementedError


class OpenAIEmbedder(BaseEmbedder):
    provider_name = "openai"

    def __init__(self, api_key: str, model_name: str, api_base: str) -> None:
        self.api_key = api_key
        self.model_name = model_name
        self.api_base = api_base

    def embed_texts(self, texts: List[str]) -> List[List[float]]:
        payload = {
            "model": self.model_name,
            "input": texts,
        }
        request = urllib.request.Request(
            self.api_base,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Accept": "application/json",
                "Content-Type": "application/json",
                "User-Agent": "curl/8.0.0",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                body = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"OpenAI-compatible embeddings API error {exc.code}: {detail}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Network error while calling embeddings API: {exc}") from exc

        response_json = json.loads(body)
        data = response_json.get("data") or []
        return [item.get("embedding") or [] for item in data]


class JinaEmbedder(BaseEmbedder):
    provider_name = "jina"

    def __init__(self, api_key: str, model_name: str, api_base: str) -> None:
        self.api_key = api_key
        self.model_name = model_name
        self.api_base = api_base

    def embed_texts(self, texts: List[str]) -> List[List[float]]:
        payload = {
            "model": self.model_name,
            "input": texts,
            "normalized": True,
            "embedding_type": "float",
        }
        task = os.getenv("JINA_EMBEDDING_TASK")
        if task:
            payload["task"] = task

        try:
            import requests
        except ImportError as exc:
            raise RuntimeError("The requests package is required for Jina embeddings.") from exc

        try:
            response = requests.post(
                self.api_base,
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
                timeout=180,
            )
            response.raise_for_status()
        except requests.HTTPError as exc:
            detail = exc.response.text if exc.response is not None else str(exc)
            raise RuntimeError(f"Jina embeddings API error {response.status_code}: {detail}") from exc
        except requests.RequestException as exc:
            raise RuntimeError(f"Network error while calling Jina embeddings API: {exc}") from exc

        response_json = response.json()
        data = response_json.get("data") or []
        return [item.get("embedding") or [] for item in data]


def create_embedder(provider: str, model: Optional[str], api_base: Optional[str]) -> BaseEmbedder:
    if provider == "openai":
        api_key = os.getenv("OPENAI_API_KEY")
        if not api_key:
            raise RuntimeError("OPENAI_API_KEY is missing for OpenAI embeddings.")
        return OpenAIEmbedder(
            api_key=api_key,
            model_name=model or os.getenv("OPENAI_EMBEDDING_MODEL") or DEFAULT_OPENAI_MODEL,
            api_base=api_base or os.getenv("OPENAI_EMBEDDING_API_BASE") or DEFAULT_OPENAI_API_BASE,
        )

    if provider == "jina":
        api_key = os.getenv("JINA_API_KEY")
        if not api_key:
            raise RuntimeError("JINA_API_KEY is missing for Jina embeddings.")
        return JinaEmbedder(
            api_key=api_key,
            model_name=model or os.getenv("JINA_EMBEDDING_MODEL") or DEFAULT_JINA_MODEL,
            api_base=api_base or os.getenv("JINA_EMBEDDING_API_BASE") or DEFAULT_JINA_API_BASE,
        )

    raise RuntimeError(
        f"Unsupported embedding provider: {provider}. Use WORKFLOW_EMBEDDING_PROVIDER=openai or jina."
    )


def attach_embeddings(
    documents: List[Dict[str, Any]], embedder: BaseEmbedder, batch_size: int
) -> Tuple[List[Dict[str, Any]], int]:
    embedded_documents: List[Dict[str, Any]] = []
    dimension = 0
    texts = [((doc.get("payload") or {}).get("text") or "") for doc in documents]

    vectors: List[List[float]] = []
    for batch in chunked(texts, batch_size):
        vectors.extend(embedder.embed_texts(batch))

    for document, vector in zip(documents, vectors):
        embedded = dict(document)
        embedded["vector"] = vector
        embedded_documents.append(embedded)
        if vector and not dimension:
            dimension = len(vector)

    return embedded_documents, dimension
