from __future__ import annotations

import re
from typing import Any, Dict, List, Optional


CODE_CHUNK_TYPES = {"method_code", "data_type_code"}


def compact_whitespace(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def normalize_embedding_text(raw_text: str) -> str:
    lines = [line.strip() for line in (raw_text or "").splitlines() if line.strip()]
    if not lines:
        return ""

    paragraphs: List[str] = []
    index = 0
    while index < len(lines):
        current = lines[index]
        if current.endswith(":") and index + 1 < len(lines):
            paragraphs.append(f"{current} {lines[index + 1]}")
            index += 2
            continue
        paragraphs.append(current)
        index += 1

    normalized: List[str] = []
    for paragraph in paragraphs:
        sentence = compact_whitespace(paragraph)
        if not sentence:
            continue
        if not sentence.endswith((".", "!", "?")):
            sentence += "."
        normalized.append(sentence)

    return " ".join(normalized)


def embedding_text_with_code(document: Dict[str, Any]) -> str:
    embedding_text = document.get("embedding_text", "")
    content = document.get("content", "")
    if (
        document.get("chunk_type") in CODE_CHUNK_TYPES
        and content
        and "Code:" not in embedding_text
    ):
        return f"{embedding_text.rstrip()}\nCode:\n{content}".strip()
    return embedding_text


def build_parser_ref(document: Dict[str, Any]) -> Optional[str]:
    graph_refs = document.get("graph_refs") or []
    if not isinstance(graph_refs, list):
        return None

    preferred_prefixes = [
        "method:",
        "class:",
        "external_service:",
        "config:",
    ]
    for prefix in preferred_prefixes:
        for ref in graph_refs:
            if isinstance(ref, str) and ref.startswith(prefix):
                return ref
    return None


def build_payload(document: Dict[str, Any]) -> Dict[str, Any]:
    source_metadata = document.get("metadata") or {}
    content = document.get("content", "")
    payload: Dict[str, Any] = {
        "text": normalize_embedding_text(embedding_text_with_code(document)),
        "chunk_type": document.get("chunk_type"),
    }
    if content:
        payload["code"] = content

    source_file = document.get("source_file")
    if source_file:
        payload["sourceFile"] = source_file

    parser_ref = build_parser_ref(document)
    if parser_ref:
        payload["parserRef"] = parser_ref

    useful_fields = [
        ("project_name", "project_name"),
        ("project_id", "project_id"),
        ("scan_id", "scan_id"),
        ("qualified_name", "qualified_name"),
        ("class_name", "class_name"),
        ("method_name", "method_name"),
        ("package", "package"),
        ("layer", "layer"),
        ("type", "type"),
        ("bucket", "bucket"),
        ("endpoint", "endpoint"),
        ("key", "key"),
        ("description", "description"),
        ("category", "category"),
        ("sensitive", "sensitive"),
        ("environments", "environments"),
        ("used_by", "used_by"),
        ("name", "name"),
        ("configs", "configs"),
        ("internal", "internal"),
    ]
    for source_key, target_key in useful_fields:
        value = source_metadata.get(source_key)
        if value not in (None, "", [], {}):
            payload[target_key] = value

    environments = payload.pop("environments", None)
    if isinstance(environments, dict):
        for env_name, env_value in environments.items():
            if env_value not in (None, "", [], {}):
                payload[f"environment_{env_name}"] = env_value

    return payload


def build_point(document: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "id": document.get("id"),
        "qdrant_id": document.get("qdrant_id"),
        "vector": None,
        "payload": build_payload(document),
    }


def build_points(vector_ready_payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    return [build_point(document) for document in vector_ready_payload.get("documents", [])]
