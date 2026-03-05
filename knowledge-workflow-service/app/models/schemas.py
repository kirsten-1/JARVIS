from datetime import datetime
from typing import Optional
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator


class ApiModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")


class IngestRequest(ApiModel):
    source_type: str = Field(default="text")
    content: str = Field(min_length=1)
    title: Optional[str] = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    chunk_size: Optional[int] = Field(default=None)
    chunk_overlap: Optional[int] = Field(default=None)

    @field_validator("source_type")
    @classmethod
    def validate_source_type(cls, value: str) -> str:
        allowed = {"text", "markdown", "html"}
        normalized = value.lower().strip()
        if normalized not in allowed:
            raise ValueError(f"source_type must be one of {sorted(allowed)}")
        return normalized


class IngestResponse(ApiModel):
    document_id: str
    chunk_count: int
    chunk_size: int
    chunk_overlap: int


class DocumentSummary(ApiModel):
    document_id: str
    title: str
    source_type: str
    metadata: dict[str, Any] = Field(default_factory=dict)
    chunk_count: int
    created_at: datetime


class ChunkView(ApiModel):
    chunk_id: str
    document_id: str
    chunk_index: int
    content: str


class ListDocumentsResponse(ApiModel):
    total: int
    items: list[DocumentSummary]


class SearchRequest(ApiModel):
    query: str = Field(min_length=1)
    top_k: int = Field(default=5, ge=1, le=50)
    document_id: Optional[str] = Field(default=None)
    search_mode: str = Field(default="hybrid")
    candidate_k: int = Field(default=20, ge=1, le=200)
    rerank: bool = Field(default=True)
    bm25_weight: float = Field(default=0.6, ge=0.0, le=1.0)
    dense_weight: float = Field(default=0.4, ge=0.0, le=1.0)

    @field_validator("search_mode")
    @classmethod
    def validate_search_mode(cls, value: str) -> str:
        allowed = {"hybrid", "bm25", "dense"}
        normalized = value.lower().strip()
        if normalized not in allowed:
            raise ValueError(f"search_mode must be one of {sorted(allowed)}")
        return normalized


class SearchItem(ApiModel):
    score: float
    bm25_score: float = 0.0
    dense_score: float = 0.0
    retrieval_score: float = 0.0
    rerank_score: Optional[float] = None
    chunk_id: str
    document_id: str
    chunk_index: int
    content: str


class SearchResponse(ApiModel):
    query: str
    strategy: str
    rerank_applied: bool
    retrieved_candidates: int
    total: int
    items: list[SearchItem]


class HealthResponse(ApiModel):
    status: str
    service: str
    env: str
