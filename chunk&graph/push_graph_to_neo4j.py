from __future__ import annotations

import base64
import json
import re
import urllib.error
import urllib.request
from typing import Any, Dict, Iterable, List


def chunked(values: List[Dict[str, Any]], size: int) -> Iterable[List[Dict[str, Any]]]:
    for start in range(0, len(values), size):
        yield values[start : start + size]


def sanitize_label(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_]", "_", value or "")
    if not cleaned:
        return "GraphNode"
    if cleaned[0].isdigit():
        cleaned = f"_{cleaned}"
    return cleaned


def basic_auth_header(username: str, password: str) -> str:
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return f"Basic {token}"


class Neo4jHttpClient:
    def __init__(self, base_url: str, username: str, password: str, database: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.database = database
        self.authorization = basic_auth_header(username, password)

    def execute(self, statement: str, parameters: Dict[str, Any] | None = None) -> Dict[str, Any]:
        url = f"{self.base_url}/db/{self.database}/query/v2"
        payload = {
            "statement": " ".join((statement or "").split()),
            "parameters": parameters or {},
        }
        request = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": self.authorization,
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                body = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Neo4j HTTP error {exc.code}: {detail}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Network error while calling Neo4j: {exc}") from exc

        response_json = json.loads(body)
        return response_json


def prepare_node_batch(nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    prepared: List[Dict[str, Any]] = []
    for node in nodes:
        metadata = node.get("metadata") or {}
        prepared.append(
            {
                "id": node.get("id"),
                "label": node.get("label"),
                "nodeType": node.get("type"),
                "metadataJson": json.dumps(metadata, ensure_ascii=False),
            }
        )
    return prepared


def prepare_edge_batch(edges: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    prepared: List[Dict[str, Any]] = []
    for edge in edges:
        metadata = edge.get("metadata") or {}
        prepared.append(
            {
                "from": edge.get("from"),
                "to": edge.get("to"),
                "metadataJson": json.dumps(metadata, ensure_ascii=False),
            }
        )
    return prepared


def create_constraints(client: Neo4jHttpClient, nodes: List[Dict[str, Any]]) -> None:
    labels = sorted({sanitize_label(node.get("type") or "GraphNode") for node in nodes})
    for label in labels:
        client.execute(
            f"""
            CREATE CONSTRAINT {label.lower()}_project_id_unique IF NOT EXISTS
            FOR (n:{label})
            REQUIRE (n.projectId, n.id) IS UNIQUE
            """
        )


def clear_graph(client: Neo4jHttpClient) -> None:
    client.execute("MATCH (n) DETACH DELETE n")


def push_nodes(client: Neo4jHttpClient, nodes: List[Dict[str, Any]], batch_size: int, project_id: str, scan_id: str) -> None:
    nodes_by_label: Dict[str, List[Dict[str, Any]]] = {}
    for node in nodes:
        label = sanitize_label(node.get("type") or "GraphNode")
        nodes_by_label.setdefault(label, []).append(node)

    for label, group in nodes_by_label.items():
        statement = f"""
        UNWIND $rows AS row
        MERGE (n:{label} {{projectId: $projectId, id: row.id}})
        SET n.label = row.label,
            n.nodeType = row.nodeType,
            n.metadataJson = row.metadataJson,
            n.projectId = $projectId,
            n.lastSeen = $scanId
        """
        for batch in chunked(prepare_node_batch(group), batch_size):
            client.execute(statement, {"rows": batch, "projectId": project_id, "scanId": scan_id})


def push_edges(client: Neo4jHttpClient, edges: List[Dict[str, Any]], batch_size: int, project_id: str, scan_id: str) -> None:
    edges_by_type: Dict[str, List[Dict[str, Any]]] = {}
    for edge in edges:
        edge_type = sanitize_label(edge.get("type") or "RELATED_TO")
        edges_by_type.setdefault(edge_type, []).append(edge)

    for edge_type, group in edges_by_type.items():
        statement = f"""
        UNWIND $rows AS row
        MATCH (from {{projectId: $projectId, id: row.from}})
        MATCH (to {{projectId: $projectId, id: row.to}})
        MERGE (from)-[r:{edge_type}]->(to)
        SET r.metadataJson = row.metadataJson,
            r.projectId = $projectId,
            r.lastSeen = $scanId
        """
        for batch in chunked(prepare_edge_batch(group), batch_size):
            client.execute(statement, {"rows": batch, "projectId": project_id, "scanId": scan_id})


def delete_stale_relationships(client: Neo4jHttpClient, project_id: str, scan_id: str) -> None:
    client.execute(
        """
        MATCH ()-[r]->()
        WHERE r.projectId = $projectId AND r.lastSeen <> $scanId
        DELETE r
        """,
        {"projectId": project_id, "scanId": scan_id},
    )


def delete_stale_nodes(client: Neo4jHttpClient, project_id: str, scan_id: str) -> None:
    client.execute(
        """
        MATCH (n)
        WHERE n.projectId = $projectId AND n.lastSeen <> $scanId
        DETACH DELETE n
        """,
        {"projectId": project_id, "scanId": scan_id},
    )
