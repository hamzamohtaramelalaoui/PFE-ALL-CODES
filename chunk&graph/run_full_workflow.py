from __future__ import annotations

from copy import deepcopy
import logging
import os
from typing import Any, Dict, Optional

from build_rag_artifacts import (
    build_artifacts,
    build_vector_ready_document,
    derive_project_id,
    repository_name_from_root,
    utc_scan_id,
)
from embed_vectordb_documents import attach_embeddings, create_embedder
from generate_summaries import create_summarizer, summarize_chunks
from prepare_vectordb_documents import build_points
from push_graph_to_neo4j import (
    Neo4jHttpClient,
    clear_graph,
    create_constraints,
    delete_stale_nodes,
    delete_stale_relationships,
    push_edges,
    push_nodes,
)
from push_to_qdrant import (
    QdrantClient,
    build_qdrant_points,
    chunked as chunked_points,
    collect_stale_point_ids,
)


logger = logging.getLogger(__name__)


def normalize_analysis_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    if not isinstance(payload, dict):
        raise RuntimeError("Workflow payload must be a JSON object.")

    if isinstance(payload.get("analysis"), dict):
        payload = payload["analysis"]

    if "repositoryAnalysis" in payload:
        analysis = dict(payload)
    elif "files" in payload:
        analysis = {"repositoryAnalysis": payload}
    else:
        raise RuntimeError(
            "Workflow payload must contain repositoryAnalysis, or an analysis object containing repositoryAnalysis."
        )

    repository_analysis = analysis.get("repositoryAnalysis")
    if not isinstance(repository_analysis, dict):
        raise RuntimeError("repositoryAnalysis must be an object.")

    files = repository_analysis.get("files")
    if files is None:
        repository_analysis["files"] = []
    elif not isinstance(files, list):
        raise RuntimeError("repositoryAnalysis.files must be a list.")

    configuration_analysis = analysis.get("configurationAnalysis")
    if configuration_analysis is None:
        analysis["configurationAnalysis"] = {"configChunks": [], "serviceDependencies": []}
    elif not isinstance(configuration_analysis, dict):
        raise RuntimeError("configurationAnalysis must be an object when provided.")
    else:
        configuration_analysis.setdefault("configChunks", [])
        configuration_analysis.setdefault("serviceDependencies", [])

    return analysis


def ensure_repository_identity(analysis: Dict[str, Any]) -> Dict[str, Any]:
    repository_analysis = analysis.get("repositoryAnalysis") or {}
    repository_root = repository_analysis.get("repositoryRoot") or ""
    repository_name = repository_name_from_root(repository_root)
    repository_info = dict(analysis.get("repository") or {})
    repository_info["project_id"] = repository_info.get("project_id") or derive_project_id(repository_root, repository_name)
    repository_info["scan_id"] = repository_info.get("scan_id") or utc_scan_id()
    analysis["repository"] = repository_info
    return analysis


def env_flag(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def env_optional(name: str) -> Optional[str]:
    value = os.getenv(name)
    if value is None:
        return None
    stripped = value.strip()
    return stripped or None


def load_workflow_settings() -> Dict[str, Any]:
    return {
        "summary_enabled": env_flag("WORKFLOW_ENABLE_SUMMARY", True),
        "summary_provider": os.getenv("SUMMARY_PROVIDER", "groq").strip().lower() or "groq",
        "summary_model": env_optional("SUMMARY_MODEL"),
        "summary_api_base": env_optional("SUMMARY_API_BASE"),
        "summary_force": env_flag("WORKFLOW_SUMMARY_FORCE", False),
        "summary_sleep_seconds": float(os.getenv("WORKFLOW_SUMMARY_SLEEP_SECONDS", "0") or "0"),
        "embedding_provider": os.getenv("WORKFLOW_EMBEDDING_PROVIDER", "openai").strip().lower() or "openai",
        "embedding_model": env_optional("WORKFLOW_EMBEDDING_MODEL"),
        "embedding_api_base": env_optional("WORKFLOW_EMBEDDING_API_BASE"),
        "embedding_batch_size": int(os.getenv("WORKFLOW_EMBEDDING_BATCH_SIZE", "32") or "32"),
        "push_qdrant": env_flag("WORKFLOW_PUSH_QDRANT", True),
        "qdrant_url": os.getenv("QDRANT_URL", "http://localhost:6333").strip(),
        "qdrant_collection": os.getenv("QDRANT_COLLECTION", "code_chunks").strip() or "code_chunks",
        "qdrant_distance": os.getenv("QDRANT_DISTANCE", "Cosine").strip() or "Cosine",
        "qdrant_batch_size": int(os.getenv("QDRANT_BATCH_SIZE", "64") or "64"),
        "qdrant_api_key": env_optional("QDRANT_API_KEY"),
        "qdrant_recreate": env_flag("QDRANT_RECREATE", False),
        "qdrant_delete_missing": env_flag("QDRANT_DELETE_MISSING", True),
        "push_neo4j": env_flag("WORKFLOW_PUSH_NEO4J", True),
        "neo4j_url": os.getenv("NEO4J_URL", "http://localhost:7474").strip(),
        "neo4j_database": os.getenv("NEO4J_DATABASE", "neo4j").strip() or "neo4j",
        "neo4j_username": os.getenv("NEO4J_USERNAME", "neo4j").strip() or "neo4j",
        "neo4j_password": env_optional("NEO4J_PASSWORD"),
        "neo4j_batch_size": int(os.getenv("NEO4J_BATCH_SIZE", "200") or "200"),
        "neo4j_recreate": env_flag("NEO4J_RECREATE", False),
        "neo4j_delete_missing": env_flag("NEO4J_DELETE_MISSING", True),
    }


def build_vector_payload(chunks_payload: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "repository": chunks_payload.get("repository") or {},
        "stats": chunks_payload.get("stats") or {},
        "documents": [build_vector_ready_document(chunk) for chunk in chunks_payload.get("chunks", [])],
    }


def embed_vector_payload(
    vector_db_payload: Dict[str, Any],
    *,
    provider: str,
    model: Optional[str],
    api_base: Optional[str],
    batch_size: int,
) -> Dict[str, Any]:
    documents = vector_db_payload.get("documents") or []
    logger.info(
        "Embedding vector payload provider=%s model=%s document_count=%s batch_size=%s",
        provider,
        model or "default",
        len(documents),
        batch_size,
    )
    embedder = create_embedder(provider, model, api_base)
    embedded_documents, dimension = attach_embeddings(documents, embedder, batch_size)
    if documents and len(embedded_documents) != len(documents):
        raise RuntimeError(
            f"Embedding provider returned {len(embedded_documents)} vectors for {len(documents)} documents."
        )
    if documents and dimension <= 0:
        raise RuntimeError("Embedding provider returned empty vectors for all documents.")
    logger.info(
        "Embedding complete provider=%s model=%s document_count=%s embedding_dimension=%s",
        embedder.provider_name,
        embedder.model_name,
        len(embedded_documents),
        dimension,
    )
    return {
        "repository": vector_db_payload.get("repository") or {},
        "vectorStatus": "embedded",
        "embeddingProvider": embedder.provider_name,
        "embeddingModel": embedder.model_name,
        "embeddingDimension": dimension,
        "documents": embedded_documents,
    }


def infer_embedding_dimension(documents: list[Dict[str, Any]]) -> int:
    for document in documents:
        vector = document.get("vector")
        if isinstance(vector, list) and vector:
            return len(vector)
    return 0


def push_embedded_payload_to_qdrant(
    embedded_payload: Dict[str, Any],
    *,
    url: str,
    collection: str,
    distance: str,
    batch_size: int,
    api_key: Optional[str],
    recreate: bool,
    delete_missing: bool,
) -> Dict[str, Any]:
    repository = embedded_payload.get("repository") or {}
    project_id = repository.get("project_id")
    scan_id = repository.get("scan_id")
    if not project_id:
        raise RuntimeError("Missing project_id in embedded payload.")
    if not scan_id:
        raise RuntimeError("Missing scan_id in embedded payload.")

    client = QdrantClient(url, api_key=api_key)
    exists = client.collection_exists(collection)
    logger.info(
        "Preparing Qdrant push collection=%s exists=%s recreate=%s delete_missing=%s project_id=%s scan_id=%s",
        collection,
        exists,
        recreate,
        delete_missing,
        project_id,
        scan_id,
    )
    if exists and recreate:
        client.delete_collection(collection)
        exists = False

    documents = embedded_payload.get("documents") or []
    if not documents:
        deleted_points = 0
        if exists and not recreate and delete_missing:
            stale_ids = collect_stale_point_ids(client, collection, project_id, scan_id)
            deleted_points = len(stale_ids)
            for batch in chunked_points([{"id": point_id} for point_id in stale_ids], batch_size):
                client.delete_points(collection, [item["id"] for item in batch])

        return {
            "collection": collection,
            "pushed_points": 0,
            "deleted_stale_points": deleted_points,
            "project_id": project_id,
            "scan_id": scan_id,
        }

    points = build_qdrant_points(documents)
    vector_size = embedded_payload.get("embeddingDimension") or infer_embedding_dimension(documents)
    logger.info(
        "Qdrant payload ready collection=%s documents=%s points=%s embeddingDimension=%s inferredDimension=%s",
        collection,
        len(documents),
        len(points),
        embedded_payload.get("embeddingDimension"),
        infer_embedding_dimension(documents),
    )
    if not isinstance(vector_size, int) or vector_size <= 0:
        raise RuntimeError("Missing or invalid embeddingDimension in embedded payload.")

    if not exists:
        client.create_collection(collection, vector_size=vector_size, distance=distance)

    for batch in chunked_points(points, batch_size):
        client.upsert_points(collection, batch)

    deleted_points = 0
    if exists and not recreate and delete_missing:
        stale_ids = collect_stale_point_ids(client, collection, project_id, scan_id)
        deleted_points = len(stale_ids)
        for batch in chunked_points([{"id": point_id} for point_id in stale_ids], batch_size):
            client.delete_points(collection, [item["id"] for item in batch])

    return {
        "collection": collection,
        "pushed_points": len(points),
        "deleted_stale_points": deleted_points,
        "project_id": project_id,
        "scan_id": scan_id,
    }


def push_graph_payload_to_neo4j(
    graph_payload: Dict[str, Any],
    *,
    url: str,
    username: str,
    password: str,
    database: str,
    batch_size: int,
    recreate: bool,
    delete_missing: bool,
) -> Dict[str, Any]:
    repository = graph_payload.get("repository") or {}
    project_id = repository.get("project_id")
    scan_id = repository.get("scan_id")
    if not project_id:
        raise RuntimeError("Missing project_id in graph payload.")
    if not scan_id:
        raise RuntimeError("Missing scan_id in graph payload.")

    nodes = graph_payload.get("nodes") or []
    edges = graph_payload.get("edges") or []
    client = Neo4jHttpClient(url, username, password, database)
    create_constraints(client, nodes)

    if recreate:
        clear_graph(client)
        create_constraints(client, nodes)

    logger.info(
        "Pushing graph to Neo4j database=%s nodes=%s edges=%s recreate=%s delete_missing=%s project_id=%s scan_id=%s",
        database,
        len(nodes),
        len(edges),
        recreate,
        delete_missing,
        project_id,
        scan_id,
    )
    push_nodes(client, nodes, batch_size, project_id, scan_id)
    push_edges(client, edges, batch_size, project_id, scan_id)

    if not recreate and delete_missing:
        delete_stale_relationships(client, project_id, scan_id)
        delete_stale_nodes(client, project_id, scan_id)

    return {
        "database": database,
        "pushed_nodes": len(nodes),
        "pushed_edges": len(edges),
        "project_id": project_id,
        "scan_id": scan_id,
    }


def run_full_workflow_from_analysis(
    analysis: Dict[str, Any],
    *,
    settings: Optional[Dict[str, Any]] = None,
    provider: Optional[str] = None,
) -> Dict[str, Any]:
    workflow_settings = settings or load_workflow_settings()
    if provider:
        provider = provider.strip().lower()
        workflow_settings = dict(workflow_settings)
        workflow_settings["summary_provider"] = provider
        if provider == "openai":
            workflow_settings["embedding_provider"] = "openai"
        elif provider == "groq":
            workflow_settings["embedding_provider"] = os.getenv("WORKFLOW_EMBEDDING_PROVIDER", "openai").strip().lower() or "openai"

    analysis_payload = ensure_repository_identity(normalize_analysis_payload(deepcopy(analysis)))
    repository_analysis = analysis_payload.get("repositoryAnalysis") or {}
    configuration_analysis = analysis_payload.get("configurationAnalysis") or {}
    repository = analysis_payload.get("repository") or {}
    logger.info(
        "Workflow started repositoryRoot=%s project_id=%s scan_id=%s files=%s configChunks=%s serviceDependencies=%s summary_enabled=%s summary_provider=%s embedding_provider=%s push_qdrant=%s push_neo4j=%s",
        repository_analysis.get("repositoryRoot"),
        repository.get("project_id"),
        repository.get("scan_id"),
        len(repository_analysis.get("files") or []),
        len(configuration_analysis.get("configChunks") or []),
        len(configuration_analysis.get("serviceDependencies") or []),
        workflow_settings["summary_enabled"],
        workflow_settings["summary_provider"],
        workflow_settings["embedding_provider"],
        workflow_settings["push_qdrant"],
        workflow_settings["push_neo4j"],
    )
    chunks_payload, graph_payload = build_artifacts(analysis_payload)
    logger.info(
        "Artifacts built chunks=%s graphNodes=%s graphEdges=%s",
        len(chunks_payload.get("chunks") or []),
        len(graph_payload.get("nodes") or []),
        len(graph_payload.get("edges") or []),
    )

    if workflow_settings["summary_enabled"]:
        logger.info(
            "Summarization started provider=%s model=%s force=%s",
            workflow_settings["summary_provider"],
            workflow_settings["summary_model"] or "default",
            workflow_settings["summary_force"],
        )
        summarizer = create_summarizer(
            workflow_settings["summary_provider"],
            workflow_settings["summary_model"],
            workflow_settings["summary_api_base"],
        )
        summarized_payload = summarize_chunks(
            chunks_payload,
            summarizer,
            limit=None,
            force=workflow_settings["summary_force"],
            sleep_seconds=workflow_settings["summary_sleep_seconds"],
        )
        logger.info("Summarization complete chunks=%s", len(summarized_payload.get("chunks") or []))
    else:
        summarized_payload = chunks_payload
        logger.info("Summarization skipped chunks=%s", len(summarized_payload.get("chunks") or []))

    summarized_vector_ready_payload = build_vector_payload(summarized_payload)
    vector_db_payload = {
        "repository": summarized_vector_ready_payload.get("repository") or {},
        "vectorStatus": "pending_embedding",
        "documents": build_points(summarized_vector_ready_payload),
    }
    logger.info("Vector payload prepared documents=%s", len(vector_db_payload.get("documents") or []))

    embedded_payload = embed_vector_payload(
        vector_db_payload,
        provider=workflow_settings["embedding_provider"],
        model=workflow_settings["embedding_model"],
        api_base=workflow_settings["embedding_api_base"],
        batch_size=workflow_settings["embedding_batch_size"],
    )

    qdrant_result: Optional[Dict[str, Any]] = None
    if workflow_settings["push_qdrant"]:
        qdrant_result = push_embedded_payload_to_qdrant(
            embedded_payload,
            url=workflow_settings["qdrant_url"],
            collection=workflow_settings["qdrant_collection"],
            distance=workflow_settings["qdrant_distance"],
            batch_size=workflow_settings["qdrant_batch_size"],
            api_key=workflow_settings["qdrant_api_key"],
            recreate=workflow_settings["qdrant_recreate"],
            delete_missing=workflow_settings["qdrant_delete_missing"],
        )
        logger.info("Qdrant push complete result=%s", qdrant_result)
    else:
        logger.info("Qdrant push skipped")

    neo4j_result: Optional[Dict[str, Any]] = None
    if workflow_settings["push_neo4j"]:
        neo4j_password = workflow_settings["neo4j_password"]
        if not neo4j_password:
            raise RuntimeError("NEO4J_PASSWORD is required in .env when WORKFLOW_PUSH_NEO4J=true.")
        neo4j_result = push_graph_payload_to_neo4j(
            graph_payload,
            url=workflow_settings["neo4j_url"],
            username=workflow_settings["neo4j_username"],
            password=neo4j_password,
            database=workflow_settings["neo4j_database"],
            batch_size=workflow_settings["neo4j_batch_size"],
            recreate=workflow_settings["neo4j_recreate"],
            delete_missing=workflow_settings["neo4j_delete_missing"],
        )
        logger.info("Neo4j push complete result=%s", neo4j_result)
    else:
        logger.info("Neo4j push skipped")

    result = {
        "status": "success",
        "repository": summarized_payload.get("repository") or {},
        "stats": summarized_payload.get("stats") or {},
        "graph": {
            "nodes": len(graph_payload.get("nodes") or []),
            "edges": len(graph_payload.get("edges") or []),
        },
        "vectordb": {
            "documents": len(vector_db_payload.get("documents") or []),
            "embedding_dimension": embedded_payload.get("embeddingDimension"),
            "embedding_provider": embedded_payload.get("embeddingProvider"),
            "embedding_model": embedded_payload.get("embeddingModel"),
        },
        "qdrant": qdrant_result,
        "neo4j": neo4j_result,
    }
    logger.info("Workflow completed status=success repository=%s", result.get("repository"))
    return result
