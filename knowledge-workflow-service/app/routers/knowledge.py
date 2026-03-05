from fastapi import APIRouter, HTTPException, Query

from app.core.config import settings
from app.models.schemas import (
    ChunkView,
    IngestRequest,
    IngestResponse,
    ListDocumentsResponse,
    SearchRequest,
    SearchResponse,
)
from app.services.pipeline import run_ingest_pipeline
from app.services.search import search_chunks
from app.services.store import KnowledgeStore


router = APIRouter()
store = KnowledgeStore(settings.data_dir)


@router.post("/ingest", response_model=IngestResponse)
def ingest_knowledge(request: IngestRequest) -> IngestResponse:
    try:
        result = run_ingest_pipeline(request, store=store, settings=settings)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return IngestResponse.model_validate(result)


@router.get("/documents", response_model=ListDocumentsResponse)
def list_documents(
    page: int = Query(default=1, ge=1),
    size: int = Query(default=20, ge=1, le=100),
) -> ListDocumentsResponse:
    docs = store.list_documents()
    total = len(docs)

    offset = (page - 1) * size
    items = docs[offset : offset + size]

    return ListDocumentsResponse(total=total, items=items)


@router.get("/documents/{document_id}/chunks", response_model=list[ChunkView])
def list_document_chunks(document_id: str) -> list[ChunkView]:
    chunks = store.list_chunks(document_id=document_id)
    if not chunks:
        raise HTTPException(status_code=404, detail="document chunks not found")
    return [ChunkView.model_validate(chunk) for chunk in chunks]


@router.post("/search", response_model=SearchResponse)
def search_knowledge(request: SearchRequest) -> SearchResponse:
    chunks = store.list_chunks(document_id=request.document_id)
    result = search_chunks(
        chunks=chunks,
        query=request.query,
        top_k=request.top_k,
        search_mode=request.search_mode,
        candidate_k=request.candidate_k,
        rerank=request.rerank,
        bm25_weight=request.bm25_weight,
        dense_weight=request.dense_weight,
    )
    return SearchResponse(
        query=request.query,
        strategy=result["strategy"],
        rerank_applied=result["rerank_applied"],
        retrieved_candidates=result["retrieved_candidates"],
        total=len(result["items"]),
        items=result["items"],
    )
