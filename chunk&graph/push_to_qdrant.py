from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any, Dict, Iterable, List, Optional


def chunked(values: List[Dict[str, Any]], size: int) -> Iterable[List[Dict[str, Any]]]:
    for start in range(0, len(values), size):
        yield values[start : start + size]


class QdrantClient:
    def __init__(self, base_url: str, api_key: Optional[str] = None) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key

    def _request(
        self,
        method: str,
        path: str,
        payload: Optional[Dict[str, Any]] = None,
        expected_statuses: Optional[List[int]] = None,
    ) -> Dict[str, Any]:
        expected_statuses = expected_statuses or [200]
        url = f"{self.base_url}{path}"
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["api-key"] = self.api_key
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                body = response.read().decode("utf-8")
                if response.status not in expected_statuses:
                    raise RuntimeError(f"Unexpected Qdrant status {response.status}: {body}")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Qdrant API error {exc.code} for {method} {path}: {detail}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Network error while calling Qdrant at {url}: {exc}") from exc
        if not body:
            return {}
        return json.loads(body)

    def collection_exists(self, collection_name: str) -> bool:
        encoded = urllib.parse.quote(collection_name, safe="")
        try:
            self._request("GET", f"/collections/{encoded}", expected_statuses=[200])
            return True
        except RuntimeError as exc:
            if "Qdrant API error 404" in str(exc):
                return False
            raise

    def delete_collection(self, collection_name: str) -> None:
        encoded = urllib.parse.quote(collection_name, safe="")
        self._request("DELETE", f"/collections/{encoded}", expected_statuses=[200])

    def create_collection(self, collection_name: str, vector_size: int, distance: str) -> None:
        encoded = urllib.parse.quote(collection_name, safe="")
        payload = {
            "vectors": {
                "size": vector_size,
                "distance": distance,
            }
        }
        self._request("PUT", f"/collections/{encoded}", payload=payload, expected_statuses=[200])

    def upsert_points(self, collection_name: str, points: List[Dict[str, Any]], wait: bool = True) -> None:
        encoded = urllib.parse.quote(collection_name, safe="")
        query = "?wait=true" if wait else ""
        self._request(
            "PUT",
            f"/collections/{encoded}/points{query}",
            payload={"points": points},
            expected_statuses=[200],
        )

    def scroll_points(
        self,
        collection_name: str,
        scroll_filter: Dict[str, Any],
        limit: int = 256,
        offset: Optional[str | int] = None,
        with_payload: bool = True,
    ) -> Dict[str, Any]:
        encoded = urllib.parse.quote(collection_name, safe="")
        payload: Dict[str, Any] = {
            "limit": limit,
            "filter": scroll_filter,
            "with_payload": with_payload,
            "with_vector": False,
        }
        if offset is not None:
            payload["offset"] = offset
        return self._request(
            "POST",
            f"/collections/{encoded}/points/scroll",
            payload=payload,
            expected_statuses=[200],
        )

    def delete_points(self, collection_name: str, point_ids: List[int | str], wait: bool = True) -> None:
        if not point_ids:
            return
        encoded = urllib.parse.quote(collection_name, safe="")
        query = "?wait=true" if wait else ""
        self._request(
            "POST",
            f"/collections/{encoded}/points/delete{query}",
            payload={"points": point_ids},
            expected_statuses=[200],
        )


def deterministic_point_id(source_id: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, source_id))


def is_valid_qdrant_point_id(value: Any) -> bool:
    if isinstance(value, int):
        return value >= 0
    if not isinstance(value, str):
        return False
    try:
        uuid.UUID(value)
        return True
    except ValueError:
        return False


def resolve_point_id(document: Dict[str, Any]) -> int | str:
    existing_point_id = document.get("qdrant_id")
    if is_valid_qdrant_point_id(existing_point_id):
        return existing_point_id

    source_id = document.get("id")
    if is_valid_qdrant_point_id(source_id):
        return source_id

    if not source_id:
        raise RuntimeError("Document is missing id")

    # Qdrant point IDs must be uint64 or UUID, so keep a stable deterministic UUID
    # when the generated document id is a semantic string such as chunk:method:...
    return deterministic_point_id(str(source_id))


def normalize_payload(document: Dict[str, Any], point_id: int | str) -> Dict[str, Any]:
    payload = dict(document.get("payload") or {})
    source_id = document.get("id")
    if source_id:
        payload["source_id"] = source_id
    payload["qdrant_id"] = point_id
    return payload


def build_qdrant_points(documents: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    points: List[Dict[str, Any]] = []
    for document in documents:
        source_id = document.get("id")
        vector = document.get("vector") or []
        if not source_id:
            raise RuntimeError("Document is missing id")
        if not vector:
            raise RuntimeError(f"Document {source_id} is missing vector")
        point_id = resolve_point_id(document)
        points.append(
            {
                "id": point_id,
                "vector": vector,
                "payload": normalize_payload(document, point_id),
            }
        )
    return points

def stale_points_filter(project_id: str, scan_id: str) -> Dict[str, Any]:
    return {
        "must": [
            {"key": "project_id", "match": {"value": project_id}},
        ],
        "must_not": [
            {"key": "scan_id", "match": {"value": scan_id}},
        ],
    }


def collect_stale_point_ids(
    client: QdrantClient,
    collection_name: str,
    project_id: str,
    scan_id: str,
) -> List[int | str]:
    point_ids: List[int | str] = []
    next_offset: Optional[str | int] = None

    while True:
        response = client.scroll_points(
            collection_name,
            scroll_filter=stale_points_filter(project_id, scan_id),
            offset=next_offset,
        )
        result = response.get("result") or {}
        points = result.get("points") or []
        for point in points:
            point_id = point.get("id")
            if point_id is not None:
                point_ids.append(point_id)

        next_offset = result.get("next_page_offset")
        if not points or next_offset is None:
            break

    return point_ids
