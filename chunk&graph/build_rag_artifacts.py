from __future__ import annotations

from datetime import datetime, timezone
from collections import defaultdict
from pathlib import Path
import re
import uuid
from typing import Any, Dict, Iterable, List, Optional, Tuple


SINGLE_CHUNK_LAYERS = {"repository", "model", "entity", "dto", "enum"}


def utc_scan_id() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def normalize_repository_root(repository_root: str) -> str:
    if not repository_root:
        return ""
    return str(Path(repository_root)).replace("\\", "/").rstrip("/")


def derive_project_id(repository_root: str, repository_name: str) -> str:
    basis = normalize_repository_root(repository_root) or repository_name or "repository"
    return str(uuid.uuid5(uuid.NAMESPACE_URL, basis))


def derive_qdrant_point_id(project_id: str, chunk_id: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"{project_id}:{chunk_id}"))


class GraphBuilder:
    def __init__(self) -> None:
        self.nodes: Dict[str, Dict[str, Any]] = {}
        self.edges: Dict[Tuple[str, str, str], Dict[str, Any]] = {}

    def add_node(self, node_id: str, node_type: str, label: str, metadata: Optional[Dict[str, Any]] = None) -> None:
        self.nodes[node_id] = {
            "id": node_id,
            "type": node_type,
            "label": label,
            "metadata": metadata or {},
        }

    def add_edge(self, from_id: str, to_id: str, edge_type: str, metadata: Optional[Dict[str, Any]] = None) -> None:
        key = (from_id, to_id, edge_type)
        if key not in self.edges:
            self.edges[key] = {
                "from": from_id,
                "to": to_id,
                "type": edge_type,
                "metadata": metadata or {},
            }

    def to_payload(self, repository_name: str, repository_root: str, project_id: str, scan_id: str) -> Dict[str, Any]:
        return {
            "repository": {
                "name": repository_name,
                "root": repository_root,
                "project_id": project_id,
                "scan_id": scan_id,
            },
            "nodes": list(self.nodes.values()),
            "edges": list(self.edges.values()),
        }


def repository_name_from_root(repository_root: str) -> str:
    return Path(repository_root).name or "repository"


def class_node_id(qualified_name: str) -> str:
    return f"class:{qualified_name}"


def file_node_id(file_path: str) -> str:
    return f"file:{file_path}"


def config_node_id(key: str) -> str:
    return f"config:{key}"


def external_service_node_id(name: str) -> str:
    return f"external_service:{name}"


def service_node_id(name: str) -> str:
    return f"service:{name}"


def endpoint_node_id(http_methods: Iterable[str], endpoint: str) -> str:
    methods = ",".join(http_methods) if http_methods else "ANY"
    return f"endpoint:{methods}:{endpoint}"


def external_endpoint_node_id(service_name: str, http_methods: Iterable[str], endpoint: str) -> str:
    methods = ",".join(http_methods) if http_methods else "ANY"
    return f"endpoint:external:{service_name}:{methods}:{endpoint}"


def normalize_type_name(type_name: Optional[str]) -> str:
    if not type_name:
        return "void"
    return " ".join(str(type_name).replace("\n", " ").split())


def method_signature(method: Dict[str, Any]) -> str:
    params = method.get("parameters") or []
    param_types = ",".join(normalize_type_name(item.get("type")) for item in params)
    return f"{method.get('name', 'unknown')}({param_types})"


def method_node_id(class_qualified_name: str, method: Dict[str, Any]) -> str:
    return f"method:{class_qualified_name}#{method_signature(method)}"


def method_ref_short(method: Dict[str, Any]) -> str:
    params = method.get("parameters") or []
    return f"{method.get('name')}({', '.join(item.get('type', '') for item in params)})"


def infer_type_bucket(file_path: str, type_info: Dict[str, Any]) -> str:
    layer = str(type_info.get("layer") or "").lower()
    qualified_name = str(type_info.get("qualifiedName") or "")
    simple_name = str(type_info.get("name") or "")
    lowered_path = file_path.lower()
    lowered_qn = qualified_name.lower()
    lowered_simple = simple_name.lower()
    type_kind = str(type_info.get("type") or "").lower()

    if layer in SINGLE_CHUNK_LAYERS:
        return layer
    if type_kind == "enum":
        return "enum"
    if "/dto/" in lowered_path or lowered_simple.endswith("dto") or ".dtos." in lowered_qn:
        return "dto"
    if "/entity/" in lowered_path or ".entity." in lowered_qn:
        return "entity"
    if "/model/" in lowered_path or ".model." in lowered_qn:
        return "model"
    if "/repository/" in lowered_path or ".repository." in lowered_qn or "/repositories/" in lowered_path:
        return "repository"
    return layer or "class"


def graph_node_type_for_bucket(type_bucket: str, type_info: Dict[str, Any]) -> str:
    mapping = {
        "controller": "Controller",
        "service": "ServiceClass",
        "repository": "RepositoryClass",
        "config": "ConfigClass",
        "dto": "DTO",
        "entity": "Entity",
        "model": "Model",
        "enum": "Enum",
    }
    if type_info.get("type") == "enum":
        return "Enum"
    return mapping.get(type_bucket, "Class")


def compact_metadata_values(metadata: Dict[str, Any]) -> Dict[str, Any]:
    return {key: value for key, value in metadata.items() if value not in (None, "", [], {})}


def simplify_fields(fields: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    simplified: List[Dict[str, Any]] = []
    for field in fields or []:
        item = {
            "name": field.get("name"),
            "type": field.get("type"),
        }
        compact = compact_metadata_values(item)
        if compact:
            simplified.append(compact)
    return simplified


def extract_path_literals_from_code(code: str) -> List[str]:
    literals = re.findall(r'"(/[^"\r\n]*)"', code or "")
    results: List[str] = []
    for literal in literals:
        value = literal.strip()
        if not value or value == "/" or value.startswith("//"):
            continue
        results.append(value)
    return list(dict.fromkeys(results))


def infer_http_methods_for_external_call(external_calls: Iterable[str]) -> List[str]:
    call_text = " ".join(external_calls or []).lower()
    if "post" in call_text:
        return ["POST"]
    if "put" in call_text:
        return ["PUT"]
    if "delete" in call_text:
        return ["DELETE"]
    if "patch" in call_text:
        return ["PATCH"]
    return ["GET"]


def guess_external_endpoint_paths(method: Dict[str, Any]) -> List[str]:
    endpoint_analysis = method.get("endpointAnalysis") or {}
    internal_full_path = endpoint_analysis.get("fullPath") or method.get("endpoint")
    internal_method_path = endpoint_analysis.get("methodPath")
    candidates = extract_path_literals_from_code(method.get("code") or "")
    results: List[str] = []
    for candidate in candidates:
        if candidate == internal_full_path:
            continue
        if internal_method_path and candidate == internal_method_path and method.get("methodRole") == "endpoint":
            results.append(candidate)
            continue
        if candidate.startswith("/"):
            results.append(candidate)
    return list(dict.fromkeys(results))


def is_single_chunk_type(type_bucket: str) -> bool:
    return type_bucket in {"repository", "model", "entity", "dto", "enum"}


def build_class_summary_content(type_info: Dict[str, Any], method_refs: List[Dict[str, Any]]) -> str:
    lines = [
        f"Class: {type_info.get('qualifiedName')}",
        f"Type: {type_info.get('type')}",
        f"Layer: {type_info.get('layer')}",
        f"Annotations: {', '.join(type_info.get('annotations') or []) or 'none'}",
        f"Extends: {', '.join(type_info.get('extendsTypes') or []) or 'none'}",
        f"Implements: {', '.join(type_info.get('implementsTypes') or []) or 'none'}",
        f"Injected dependencies: {', '.join(type_info.get('injectedDependencies') or []) or 'none'}",
        f"Internal dependencies: {', '.join(type_info.get('internalDependencies') or []) or 'none'}",
        f"External dependencies: {', '.join(type_info.get('externalDependencies') or []) or 'none'}",
        "Methods:",
    ]
    if method_refs:
        for ref in method_refs:
            endpoint = f" endpoint={ref['endpoint']}" if ref.get("endpoint") else ""
            lines.append(f"- {ref['signature']} -> {ref['id']}{endpoint}")
    else:
        lines.append("- none")
    return "\n".join(lines)


def build_config_content(config_chunk: Dict[str, Any]) -> str:
    values = config_chunk.get("environments") or config_chunk.get("values") or {}
    used_by = config_chunk.get("usedBy") or []
    lines = [
        f"Config key: {config_chunk.get('key')}",
        f"Description: {config_chunk.get('description') or config_chunk.get('summary')}",
        f"Category: {config_chunk.get('category')}",
        f"Sensitive: {config_chunk.get('sensitive')}",
        "Environments:",
    ]
    for env, value in values.items():
        lines.append(f"- {env}: {value}")
    lines.append("Used by:")
    if used_by:
        lines.extend(f"- {item}" for item in used_by)
    else:
        lines.append("- none")
    return "\n".join(lines)


def build_external_service_content(service_dep: Dict[str, Any]) -> str:
    lines = [
        f"External service: {service_dep.get('name')}",
        f"Type: {service_dep.get('type')}",
        f"Category: {service_dep.get('category')}",
        f"Internal: {service_dep.get('internal')}",
        f"Description: {service_dep.get('description')}",
        f"Configs: {', '.join(service_dep.get('configs') or []) or 'none'}",
        "Environments:",
    ]
    for env, value in (service_dep.get("environments") or {}).items():
        lines.append(f"- {env}: {value}")
    lines.append("Used by:")
    used_by = service_dep.get("usedBy") or []
    if used_by:
        lines.extend(f"- {item}" for item in used_by)
    else:
        lines.append("- none")
    return "\n".join(lines)


def create_chunk(
    *,
    chunk_id: str,
    chunk_type: str,
    title: str,
    content: str,
    source_file: Optional[str],
    metadata: Dict[str, Any],
    graph_refs: List[str],
    project_id: Optional[str] = None,
    scan_id: Optional[str] = None,
) -> Dict[str, Any]:
    summary = ""
    embedding_text = create_embedding_text(chunk_type, title, metadata, content, summary)
    chunk_payload = {
        "project_id": project_id,
        "scan_id": scan_id,
        **metadata,
    }
    chunk_payload = compact_metadata_values(chunk_payload)
    return {
        "id": chunk_id,
        "chunk_type": chunk_type,
        "title": title,
        "language": "java",
        "source_file": source_file,
        "metadata": chunk_payload,
        "content": content,
        "summary": summary,
        "summary_status": "pending_llm",
        "embedding_text": embedding_text,
        "graph_refs": graph_refs,
    }




def truncate_text(value: str, limit: int = 2400) -> str:
    compact = " ".join((value or "").split())
    if len(compact) <= limit:
        return compact
    return compact[: limit - 3].rstrip() + "..."


def join_non_empty(parts: Iterable[Optional[str]]) -> str:
    return "\n".join(part for part in parts if part)


def format_list(values: Iterable[Any], limit: int = 12) -> Optional[str]:
    items = [str(value).strip() for value in values if str(value).strip()]
    if not items:
        return None
    if len(items) > limit:
        return ", ".join(items[:limit]) + ", ..."
    return ", ".join(items)


def format_inputs(parameters: Iterable[Dict[str, Any]], limit: int = 12) -> Optional[str]:
    rendered: List[str] = []
    for parameter in parameters:
        name = str(parameter.get("name") or "").strip()
        param_type = str(parameter.get("type") or "").strip()
        if name and param_type:
            rendered.append(f"{name} ({param_type})")
        elif name:
            rendered.append(name)
        elif param_type:
            rendered.append(param_type)
    return format_list(rendered, limit=limit)


def labeled(label: str, value: Optional[str]) -> Optional[str]:
    if not value:
        return None
    return f"{label}: {value}"


def code_section(content: str, limit: int) -> Optional[str]:
    code = truncate_text(content, limit)
    if not code:
        return None
    return f"Code:\n{code}"


def create_embedding_text(
    chunk_type: str,
    title: str,
    metadata: Dict[str, Any],
    content: str,
    summary: str = "",
) -> str:
    project_name = metadata.get("project_name")
    summary = (summary or "").strip()

    if chunk_type == "class_summary":
        method_refs = metadata.get("method_refs") or []
        method_names = format_list((item.get("signature") for item in method_refs), limit=20)
        endpoints = format_list((item.get("endpoint") for item in method_refs if item.get("endpoint")), limit=12)
        return join_non_empty([
            labeled("Project", project_name),
            labeled("Class", metadata.get("class_name") or title),
            labeled("Qualified name", metadata.get("qualified_name")),
            labeled("Role", metadata.get("layer") or metadata.get("type")),
            f"Description:\n{summary}" if summary else None,
            labeled("Annotations", format_list(metadata.get("annotations") or [])),
            labeled("Collaborators", format_list(metadata.get("collaborators") or [])),
            labeled("Internal dependencies", format_list(metadata.get("internal_dependencies") or [])),
            labeled("External dependencies", format_list(metadata.get("external_dependencies") or [])),
            labeled("Methods", method_names),
            labeled("Endpoints", endpoints),
        ])

    if chunk_type == "method_code":
        params = metadata.get("parameters") or []
        param_text = format_inputs(params, limit=12)
        http_methods = format_list(metadata.get("http_methods") or [], limit=6)
        dependencies = format_list(metadata.get("dependencies") or [], limit=12)
        internal_calls = format_list(metadata.get("internal_calls") or [], limit=12)
        external_calls = format_list(metadata.get("external_calls") or [], limit=12)
        framework_calls = format_list(metadata.get("framework_calls") or [], limit=12)
        constants_used = format_list(metadata.get("constants_used") or [], limit=12)
        return join_non_empty([
            labeled("Project", project_name),
            labeled("Method", metadata.get("method_name") or title),
            labeled("Class", metadata.get("class_name") or metadata.get("qualified_name")),
            labeled("Role", metadata.get("method_role")),
            f"Description:\n{summary}" if summary else None,
            labeled("Inputs", param_text),
            labeled("Returns", metadata.get("return_type")),
            labeled("Resolved endpoint", metadata.get("endpoint") or metadata.get("resolved_endpoint")),
            labeled("HTTP methods", http_methods),
            labeled("Dependencies", dependencies),
            labeled("Internal calls", internal_calls),
            labeled("External calls", external_calls),
            labeled("Framework calls", framework_calls),
            labeled("Constants used", constants_used),
            code_section(content, 3000),
        ])

    if chunk_type == "data_type_code":
        fields = metadata.get("fields") or []
        field_names = format_list((f"{item.get('type')} {item.get('name')}".strip() for item in fields), limit=25)
        method_names = format_list(metadata.get("method_names") or [], limit=20)
        return join_non_empty([
            labeled("Project", project_name),
            labeled("Type", metadata.get("class_name") or title),
            labeled("Qualified name", metadata.get("qualified_name")),
            labeled("Role", metadata.get("bucket") or metadata.get("type")),
            f"Description:\n{summary}" if summary else None,
            labeled("Annotations", format_list(metadata.get("annotations") or [])),
            labeled("Fields", field_names),
            labeled("Method names", method_names),
            code_section(content, 2600),
        ])

    if chunk_type == "config_property":
        values = metadata.get("environments") or metadata.get("values") or {}
        value_text = format_list((f"{env}={value}" for env, value in list(values.items())[:10]), limit=10)
        return join_non_empty([
            labeled("Project", project_name),
            labeled("Configuration key", metadata.get("key") or title),
            labeled("Category", metadata.get("category")),
            labeled("Sensitive", str(metadata.get("sensitive")) if metadata.get("sensitive") is not None else None),
            f"Description:\n{summary}" if summary else None,
            labeled("Property description", metadata.get("description")),
            labeled("Environments", value_text),
            labeled("Used by", format_list(metadata.get("used_by") or [], limit=12)),
        ])

    if chunk_type == "external_service_dependency":
        return join_non_empty([
            labeled("Project", project_name),
            labeled("External service", metadata.get("name") or title),
            labeled("Type", metadata.get("type")),
            labeled("Category", metadata.get("category")),
            f"Description:\n{summary}" if summary else None,
            labeled("Configs", format_list(metadata.get("configs") or [], limit=12)),
            labeled("Used by", format_list(metadata.get("used_by") or [], limit=12)),
        ])

    return join_non_empty([
        labeled("Project", project_name),
        labeled("Title", title),
        f"Description:\n{summary}" if summary else None,
        code_section(content, 2200),
    ])


def build_vector_ready_document(chunk: Dict[str, Any]) -> Dict[str, Any]:
    metadata = chunk.get("metadata") or {}
    summary = chunk.get("summary", "")
    project_id = metadata.get("project_id")
    embedding_text = create_embedding_text(
        chunk.get("chunk_type", ""),
        chunk.get("title", ""),
        metadata,
        chunk.get("content", ""),
        summary,
    )
    compact_metadata = {
        key: value
        for key, value in {
            "project_name": metadata.get("project_name"),
            "project_id": metadata.get("project_id"),
            "scan_id": metadata.get("scan_id"),
            "qualified_name": metadata.get("qualified_name"),
            "class_name": metadata.get("class_name"),
            "method_name": metadata.get("method_name"),
            "signature": metadata.get("signature"),
            "package": metadata.get("package"),
            "layer": metadata.get("layer"),
            "type": metadata.get("type"),
            "bucket": metadata.get("bucket"),
            "endpoint": metadata.get("endpoint"),
            "http_methods": metadata.get("http_methods"),
            "key": metadata.get("key"),
            "description": metadata.get("description"),
            "category": metadata.get("category"),
            "sensitive": metadata.get("sensitive"),
            "environments": metadata.get("environments") or metadata.get("values"),
            "used_by": metadata.get("used_by"),
            "name": metadata.get("name"),
            "configs": metadata.get("configs"),
            "internal": metadata.get("internal"),
        }.items()
        if value not in (None, [], "")
    }
    return {
        "id": chunk.get("id"),
        "qdrant_id": derive_qdrant_point_id(project_id, chunk.get("id")) if project_id and chunk.get("id") else None,
        "chunk_type": chunk.get("chunk_type"),
        "title": chunk.get("title"),
        "source_file": chunk.get("source_file"),
        "content": chunk.get("content", ""),
        "summary": summary,
        "summary_status": chunk.get("summary_status"),
        "embedding_text": embedding_text,
        "metadata": compact_metadata,
        "graph_refs": chunk.get("graph_refs") or [],
    }


def resolve_type_reference(name: str, qualified_by_simple: Dict[str, str], qualified_ids: Dict[str, str]) -> Optional[str]:
    if not name:
        return None
    clean = normalize_type_name(name)
    clean = clean.replace("[]", "")
    if "<" in clean:
        clean = clean.split("<", 1)[0]
    if clean in qualified_ids:
        return clean
    simple = clean.split(".")[-1]
    return qualified_by_simple.get(simple)


def resolve_method_reference(
    target: str,
    methods_by_short_owner: Dict[Tuple[str, str], List[str]],
    methods_by_qualified_owner: Dict[Tuple[str, str], List[str]],
) -> List[str]:
    if not target or "." not in target:
        return []
    owner, method_name = target.rsplit(".", 1)
    return methods_by_qualified_owner.get((owner, method_name)) or methods_by_short_owner.get((owner, method_name)) or []


def build_artifacts(analysis: Dict[str, Any]) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    repository_analysis = analysis.get("repositoryAnalysis") or {}
    configuration_analysis = analysis.get("configurationAnalysis") or {}
    repository_root = repository_analysis.get("repositoryRoot") or ""
    repo_name = repository_name_from_root(repository_root)
    project_id = str((analysis.get("repository") or {}).get("project_id") or derive_project_id(repository_root, repo_name))
    scan_id = str((analysis.get("repository") or {}).get("scan_id") or utc_scan_id())

    graph = GraphBuilder()
    repo_node_id = f"repository:{repo_name}"
    svc_node_id = service_node_id(repo_name)
    graph.add_node(repo_node_id, "Repository", repo_name, {"root": repository_root})
    graph.add_node(svc_node_id, "Service", repo_name, {"root": repository_root, "repositoryId": repo_node_id})
    graph.add_edge(repo_node_id, svc_node_id, "OWNS")

    files = repository_analysis.get("files") or []
    config_chunks = configuration_analysis.get("configChunks") or []
    service_dependencies = configuration_analysis.get("serviceDependencies") or []

    qualified_by_simple: Dict[str, str] = {}
    qualified_ids: Dict[str, str] = {}
    methods_by_short_owner: Dict[Tuple[str, str], List[str]] = defaultdict(list)
    methods_by_qualified_owner: Dict[Tuple[str, str], List[str]] = defaultdict(list)
    method_usage_lookup: Dict[str, List[str]] = defaultdict(list)
    config_usage_lookup: Dict[str, List[str]] = defaultdict(list)
    method_info_by_id: Dict[str, Dict[str, Any]] = {}
    chunks: List[Dict[str, Any]] = []

    # First pass: create repository, file, class, method, endpoint, config, external service graph nodes.
    for file_info in files:
        file_path = file_info.get("filePath")
        file_id = file_node_id(file_path)
        graph.add_node(file_id, "File", Path(file_path).name, {"filePath": file_path, "package": file_info.get("packageName")})
        graph.add_edge(repo_node_id, file_id, "HAS_FILE")

        for type_info in file_info.get("types") or []:
            qualified_name = type_info.get("qualifiedName") or type_info.get("name")
            class_id = class_node_id(qualified_name)
            type_bucket = infer_type_bucket(file_path, type_info)
            graph.add_node(
                class_id,
                graph_node_type_for_bucket(type_bucket, type_info),
                type_info.get("name"),
                {
                    "qualifiedName": qualified_name,
                    "filePath": file_path,
                    "package": file_info.get("packageName"),
                    "layer": type_info.get("layer"),
                    "type": type_info.get("type"),
                    "bucket": type_bucket,
                },
            )
            graph.add_edge(svc_node_id, class_id, "CONTAINS_COMPONENT")
            graph.add_edge(file_id, class_id, "DECLARES_CLASS")
            qualified_ids[qualified_name] = class_id
            simple_name = type_info.get("name")
            if simple_name and simple_name not in qualified_by_simple:
                qualified_by_simple[simple_name] = qualified_name

            for method in type_info.get("methods") or []:
                m_id = method_node_id(qualified_name, method)
                graph.add_node(
                    m_id,
                    "Method",
                    method_ref_short(method),
                    {
                        "classId": class_id,
                        "classQualifiedName": qualified_name,
                        "returnType": method.get("returnType"),
                        "visibility": method.get("visibility"),
                        "methodRole": method.get("methodRole"),
                        "endpoint": method.get("endpoint"),
                    },
                )
                graph.add_edge(class_id, m_id, "DECLARES_METHOD")
                method_info_by_id[m_id] = method
                methods_by_short_owner[(type_info.get("name"), method.get("name"))].append(m_id)
                methods_by_qualified_owner[(qualified_name, method.get("name"))].append(m_id)

                endpoint = method.get("endpoint")
                endpoint_analysis = method.get("endpointAnalysis") or {}
                if endpoint:
                    http_methods = method.get("httpMethods") or endpoint_analysis.get("httpMethods") or []
                    ep_id = endpoint_node_id(http_methods, endpoint)
                    graph.add_node(
                        ep_id,
                        "Endpoint",
                        endpoint_analysis.get("canonicalEndpointId") or f"{','.join(http_methods) or 'ANY'} {endpoint}",
                        {
                            "endpoint": endpoint,
                            "httpMethods": http_methods,
                            "canonicalEndpointId": endpoint_analysis.get("canonicalEndpointId"),
                            "basePath": method.get("basePath"),
                        },
                    )
                    graph.add_edge(m_id, ep_id, "EXPOSES")

    # Chunks from code analysis.
    for file_info in files:
        file_path = file_info.get("filePath")
        for type_info in file_info.get("types") or []:
            qualified_name = type_info.get("qualifiedName") or type_info.get("name")
            class_id = class_node_id(qualified_name)
            type_bucket = infer_type_bucket(file_path, type_info)
            methods = type_info.get("methods") or []

            if is_single_chunk_type(type_bucket):
                chunk_id = f"chunk:data_type:{qualified_name}"
                chunks.append(
                    create_chunk(
                        chunk_id=chunk_id,
                        chunk_type="data_type_code",
                        title=qualified_name,
                        content=type_info.get("code") or "",
                        source_file=file_path,
                        metadata={
                            "project_name": repo_name,
                            "qualified_name": qualified_name,
                            "class_name": type_info.get("name"),
                            "package": file_info.get("packageName"),
                            "layer": type_info.get("layer"),
                            "type": type_info.get("type"),
                            "bucket": type_bucket,
                            "annotations": type_info.get("annotations") or [],
                            "fields": simplify_fields(type_info.get("fields") or []),
                            "method_names": [method.get("name") for method in methods],
                        },
                        graph_refs=[class_id],
                        project_id=project_id,
                        scan_id=scan_id,
                    )
                )
            else:
                method_refs = []
                for method in methods:
                    m_id = method_node_id(qualified_name, method)
                    ref = {
                        "id": m_id,
                        "name": method.get("name"),
                        "signature": method_ref_short(method),
                        "return_type": method.get("returnType"),
                        "method_role": method.get("methodRole"),
                        "endpoint": method.get("endpoint"),
                    }
                    method_refs.append(ref)

                    method_chunk_id = f"chunk:method:{qualified_name}#{method_signature(method)}"
                    endpoint_refs = []
                    if method.get("endpoint"):
                        endpoint_refs.append(endpoint_node_id(method.get("httpMethods") or [], method.get("endpoint")))
                    chunks.append(
                        create_chunk(
                            chunk_id=method_chunk_id,
                            chunk_type="method_code",
                            title=f"{qualified_name}#{method_ref_short(method)}",
                            content=method.get("code") or "",
                            source_file=file_path,
                            metadata={
                                "project_name": repo_name,
                                "qualified_name": qualified_name,
                                "class_name": type_info.get("name"),
                                "method_name": method.get("name"),
                                "signature": method_ref_short(method),
                                "return_type": method.get("returnType"),
                                "parameters": method.get("parameters") or [],
                                "annotations": method.get("annotations") or [],
                                "http_methods": method.get("httpMethods") or [],
                                "endpoint": method.get("endpoint"),
                                "method_role": method.get("methodRole"),
                                "dependencies": method.get("dependencies") or [],
                                "internal_dependencies": method.get("internalDependencies") or [],
                                "internal_calls": method.get("internalCalls") or [],
                                "external_calls": method.get("externalCalls") or [],
                                "framework_calls": method.get("frameworkCalls") or [],
                                "constants_used": method.get("constantsUsed") or [],
                            },
                            graph_refs=[class_id, m_id, *endpoint_refs],
                            project_id=project_id,
                            scan_id=scan_id,
                        )
                    )

                summary_chunk_id = f"chunk:class_summary:{qualified_name}"
                chunks.append(
                    create_chunk(
                        chunk_id=summary_chunk_id,
                        chunk_type="class_summary",
                        title=qualified_name,
                        content=build_class_summary_content(type_info, method_refs),
                        source_file=file_path,
                        metadata={
                            "project_name": repo_name,
                            "qualified_name": qualified_name,
                            "class_name": type_info.get("name"),
                            "package": file_info.get("packageName"),
                            "layer": type_info.get("layer"),
                            "type": type_info.get("type"),
                            "bucket": type_bucket,
                            "annotations": type_info.get("annotations") or [],
                            "internal_dependencies": type_info.get("internalDependencies") or [],
                            "external_dependencies": type_info.get("externalDependencies") or [],
                            "collaborators": type_info.get("collaborators") or [],
                            "method_refs": method_refs,
                        },
                        graph_refs=[class_id, *[item["id"] for item in method_refs]],
                        project_id=project_id,
                        scan_id=scan_id,
                    )
                )

            # Graph edges for class relationships.
            for dep in type_info.get("internalDependencies") or []:
                resolved = resolve_type_reference(dep, qualified_by_simple, qualified_ids)
                if resolved:
                    graph.add_edge(class_id, class_node_id(resolved), "DEPENDS_ON")

            for implemented in type_info.get("implementsTypes") or []:
                resolved = resolve_type_reference(implemented, qualified_by_simple, qualified_ids)
                if resolved:
                    graph.add_edge(class_id, class_node_id(resolved), "IMPLEMENTS")

            for extended in type_info.get("extendsTypes") or []:
                resolved = resolve_type_reference(extended, qualified_by_simple, qualified_ids)
                if resolved:
                    graph.add_edge(class_id, class_node_id(resolved), "EXTENDS")

            for method in methods:
                m_id = method_node_id(qualified_name, method)
                for target in method.get("internalCalls") or []:
                    for resolved_method_id in resolve_method_reference(target, methods_by_short_owner, methods_by_qualified_owner):
                        graph.add_edge(m_id, resolved_method_id, "CALLS")

                return_type_target = resolve_type_reference(method.get("returnType"), qualified_by_simple, qualified_ids)
                if return_type_target:
                    graph.add_edge(m_id, class_node_id(return_type_target), "RETURNS")

                for parameter in method.get("parameters") or []:
                    resolved_param_type = resolve_type_reference(parameter.get("type"), qualified_by_simple, qualified_ids)
                    if resolved_param_type:
                        graph.add_edge(m_id, class_node_id(resolved_param_type), "ACCEPTS", {"parameter": parameter.get("name")})

    # Config nodes and chunks.
    for config_chunk in config_chunks:
        cfg_id = config_node_id(config_chunk.get("key"))
        graph.add_node(
            cfg_id,
            "ConfigKey",
            config_chunk.get("key"),
            {
                "category": config_chunk.get("category"),
                "sensitive": config_chunk.get("sensitive"),
                "summary": config_chunk.get("summary"),
            },
        )
        graph.add_edge(repo_node_id, cfg_id, "HAS_CONFIG")
        graph.add_edge(svc_node_id, cfg_id, "CONTAINS_COMPONENT")
        chunk_id = f"chunk:config:{config_chunk.get('key')}"
        chunks.append(
            create_chunk(
                chunk_id=chunk_id,
                chunk_type="config_property",
                title=config_chunk.get("key"),
                content=build_config_content(config_chunk),
                source_file=None,
                metadata={
                    "project_name": repo_name,
                    "key": config_chunk.get("key"),
                    "description": config_chunk.get("description") or config_chunk.get("summary"),
                    "category": config_chunk.get("category"),
                    "sensitive": config_chunk.get("sensitive"),
                    "environments": config_chunk.get("environments") or config_chunk.get("values") or {},
                    "used_by": config_chunk.get("usedBy") or [],
                },
                graph_refs=[cfg_id],
                project_id=project_id,
                scan_id=scan_id,
            )
        )
        for used_by in config_chunk.get("usedBy") or []:
            for resolved_method_id in resolve_method_reference(used_by, methods_by_short_owner, methods_by_qualified_owner):
                graph.add_edge(resolved_method_id, cfg_id, "USES_CONFIG")
                method_usage_lookup[resolved_method_id].append(cfg_id)
                config_usage_lookup[cfg_id].append(resolved_method_id)

    # External service nodes and chunks.
    for service_dep in service_dependencies:
        service_id = external_service_node_id(service_dep.get("name"))
        graph.add_node(
            service_id,
            "ExternalService",
            service_dep.get("name"),
            {
                "type": service_dep.get("type"),
                "category": service_dep.get("category"),
                "internal": service_dep.get("internal"),
                "description": service_dep.get("description"),
            },
        )
        graph.add_edge(repo_node_id, service_id, "HAS_EXTERNAL_SERVICE")
        graph.add_edge(svc_node_id, service_id, "CONTAINS_COMPONENT")
        external_repo_id = f"repository:{service_dep.get('name')}"
        graph.add_node(
            external_repo_id,
            "RepositoryReference",
            service_dep.get("name"),
            {"externalServiceId": service_id},
        )
        graph.add_edge(service_id, external_repo_id, "REPRESENTS")
        for config_key in service_dep.get("configs") or []:
            graph.add_edge(config_node_id(config_key), service_id, "RESOLVES_TO")

        for used_by in service_dep.get("usedBy") or []:
            for resolved_method_id in resolve_method_reference(used_by, methods_by_short_owner, methods_by_qualified_owner):
                graph.add_edge(resolved_method_id, service_id, "CALLS_EXTERNAL")
                method_info = method_info_by_id.get(resolved_method_id) or {}
                external_paths = guess_external_endpoint_paths(method_info)
                http_methods = infer_http_methods_for_external_call(method_info.get("externalCalls") or [])
                for path in external_paths:
                    endpoint_id = external_endpoint_node_id(service_dep.get("name"), http_methods, path)
                    graph.add_node(
                        endpoint_id,
                        "Endpoint",
                        f"{','.join(http_methods)} {path}",
                        {
                            "endpoint": path,
                            "httpMethods": http_methods,
                            "external": True,
                            "service": service_dep.get("name"),
                        },
                    )
                    graph.add_edge(resolved_method_id, endpoint_id, "CALLS_ENDPOINT")
                    graph.add_edge(endpoint_id, service_id, "RESOLVES_TO")

        for config_key in service_dep.get("configs") or []:
            for resolved_method_id in config_usage_lookup.get(config_node_id(config_key), []):
                graph.add_edge(resolved_method_id, service_id, "CALLS_EXTERNAL", {"viaConfig": config_key})
                method_info = method_info_by_id.get(resolved_method_id) or {}
                external_paths = guess_external_endpoint_paths(method_info)
                http_methods = infer_http_methods_for_external_call(method_info.get("externalCalls") or [])
                for path in external_paths:
                    endpoint_id = external_endpoint_node_id(service_dep.get("name"), http_methods, path)
                    graph.add_node(
                        endpoint_id,
                        "Endpoint",
                        f"{','.join(http_methods)} {path}",
                        {
                            "endpoint": path,
                            "httpMethods": http_methods,
                            "external": True,
                            "service": service_dep.get("name"),
                            "viaConfig": config_key,
                        },
                    )
                    graph.add_edge(resolved_method_id, endpoint_id, "CALLS_ENDPOINT")
                    graph.add_edge(endpoint_id, service_id, "RESOLVES_TO")

        chunk_id = f"chunk:external_service:{service_dep.get('name')}"
        chunks.append(
            create_chunk(
                chunk_id=chunk_id,
                chunk_type="external_service_dependency",
                title=service_dep.get("name"),
                content=build_external_service_content(service_dep),
                source_file=None,
                metadata={
                    "project_name": repo_name,
                    "name": service_dep.get("name"),
                    "description": service_dep.get("description"),
                    "type": service_dep.get("type"),
                    "category": service_dep.get("category"),
                    "internal": service_dep.get("internal"),
                    "configs": service_dep.get("configs") or [],
                    "environments": service_dep.get("environments") or {},
                    "used_by": service_dep.get("usedBy") or [],
                },
                graph_refs=[service_id, *[config_node_id(item) for item in service_dep.get("configs") or []]],
                project_id=project_id,
                scan_id=scan_id,
            )
        )

    chunks_payload = {
        "repository": {
            "name": repo_name,
            "root": repository_root,
            "project_id": project_id,
            "scan_id": scan_id,
        },
        "stats": {
            "total_chunks": len(chunks),
            "class_summary_chunks": sum(1 for item in chunks if item["chunk_type"] == "class_summary"),
            "method_code_chunks": sum(1 for item in chunks if item["chunk_type"] == "method_code"),
            "data_type_chunks": sum(1 for item in chunks if item["chunk_type"] == "data_type_code"),
            "config_chunks": sum(1 for item in chunks if item["chunk_type"] == "config_property"),
            "external_service_chunks": sum(1 for item in chunks if item["chunk_type"] == "external_service_dependency"),
        },
        "chunks": chunks,
    }
    graph_payload = graph.to_payload(repo_name, repository_root, project_id, scan_id)
    return chunks_payload, graph_payload


