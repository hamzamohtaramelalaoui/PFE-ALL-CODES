from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Dict, Literal, Optional

from fastapi import Body, FastAPI, HTTPException, Query

from generate_summaries import load_dotenv
from run_full_workflow import run_full_workflow_from_analysis


logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

load_dotenv(Path(__file__).with_name(".env"))

app = FastAPI(
    title="Chunk and Graph Workflow API",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


@app.post("/workflow")
def workflow_endpoint(
    analysis: Dict[str, Any] = Body(...),
    provider: Optional[Literal["groq", "openai"]] = Query(default=None),
) -> Dict[str, Any]:
    repository_analysis = analysis.get("repositoryAnalysis") or {}
    configuration_analysis = analysis.get("configurationAnalysis") or {}
    logger.info(
        "Received workflow request provider=%s topLevelKeys=%s repositoryRoot=%s files=%s configChunks=%s serviceDependencies=%s",
        provider,
        sorted(analysis.keys()),
        repository_analysis.get("repositoryRoot"),
        len(repository_analysis.get("files") or []),
        len(configuration_analysis.get("configChunks") or []),
        len(configuration_analysis.get("serviceDependencies") or []),
    )
    try:
        result = run_full_workflow_from_analysis(analysis, provider=provider)
        logger.info("Workflow request completed status=%s", result.get("status"))
        return result
    except RuntimeError as exc:
        logger.exception("Workflow request failed with client error: %s", exc)
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("Workflow request failed with server error: %s", exc)
        raise HTTPException(status_code=500, detail=str(exc)) from exc
