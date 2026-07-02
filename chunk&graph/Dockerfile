FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY requirements-api.txt ./
RUN pip install -r requirements-api.txt

COPY build_rag_artifacts.py ./
COPY embed_vectordb_documents.py ./
COPY generate_summaries.py ./
COPY prepare_vectordb_documents.py ./
COPY push_graph_to_neo4j.py ./
COPY push_to_qdrant.py ./
COPY run_full_workflow.py ./
COPY workflow_api.py ./

EXPOSE 8000

CMD ["uvicorn", "workflow_api:app", "--host", "0.0.0.0", "--port", "8000"]
