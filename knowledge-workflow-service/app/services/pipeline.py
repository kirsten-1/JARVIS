from app.core.config import Settings
from app.models.schemas import IngestRequest
from app.services.chunker import chunk_text
from app.services.parser import parse_content
from app.services.store import KnowledgeStore


def run_ingest_pipeline(request: IngestRequest, store: KnowledgeStore, settings: Settings) -> dict:
    chunk_size = request.chunk_size or settings.default_chunk_size
    chunk_overlap = request.chunk_overlap or settings.default_chunk_overlap

    if chunk_size > settings.max_chunk_size:
        raise ValueError(f"chunkSize must be <= {settings.max_chunk_size}")
    if chunk_overlap > settings.max_chunk_overlap:
        raise ValueError(f"chunkOverlap must be <= {settings.max_chunk_overlap}")
    if chunk_overlap >= chunk_size:
        raise ValueError("chunkOverlap must be smaller than chunkSize")

    parsed_text = parse_content(request.source_type, request.content)
    chunks = chunk_text(parsed_text, chunk_size=chunk_size, chunk_overlap=chunk_overlap)
    if not chunks:
        raise ValueError("content has no usable text after parsing")

    title = request.title.strip() if request.title else "untitled"
    document_id = store.create_document(
        title=title,
        source_type=request.source_type,
        metadata=request.metadata,
        chunk_count=len(chunks),
    )
    store.add_chunks(document_id, chunks)

    return {
        "document_id": document_id,
        "chunk_count": len(chunks),
        "chunk_size": chunk_size,
        "chunk_overlap": chunk_overlap,
    }
