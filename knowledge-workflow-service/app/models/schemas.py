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


class WorkflowNodeDefinition(ApiModel):
    node_id: str = Field(min_length=1)
    node_type: str = Field(min_length=1)
    config: dict[str, Any] = Field(default_factory=dict)

    @field_validator("node_type")
    @classmethod
    def validate_node_type(cls, value: str) -> str:
        allowed = {"start", "task", "condition", "approval", "webhook", "end"}
        normalized = value.lower().strip()
        if normalized not in allowed:
            raise ValueError(f"node_type must be one of {sorted(allowed)}")
        return normalized


class WorkflowEdgeDefinition(ApiModel):
    from_node: str = Field(min_length=1)
    to_node: str = Field(min_length=1)
    condition: Optional[str] = None


class WorkflowCreateRequest(ApiModel):
    name: str = Field(min_length=1)
    description: Optional[str] = None
    nodes: list[WorkflowNodeDefinition] = Field(min_length=1)
    edges: list[WorkflowEdgeDefinition] = Field(min_length=1)
    metadata: dict[str, Any] = Field(default_factory=dict)


class WorkflowSummary(ApiModel):
    workflow_id: str
    name: str
    description: Optional[str] = None
    node_count: int
    edge_count: int
    metadata: dict[str, Any] = Field(default_factory=dict)
    created_at: datetime


class WorkflowDetail(ApiModel):
    workflow_id: str
    name: str
    description: Optional[str] = None
    nodes: list[WorkflowNodeDefinition]
    edges: list[WorkflowEdgeDefinition]
    metadata: dict[str, Any] = Field(default_factory=dict)
    created_at: datetime


class WorkflowListResponse(ApiModel):
    total: int
    items: list[WorkflowSummary]


class WorkflowCreateResponse(ApiModel):
    workflow_id: str
    name: str
    created_at: datetime


class WorkflowRunRequest(ApiModel):
    input: dict[str, Any] = Field(default_factory=dict)
    idempotency_key: Optional[str] = None
    max_steps: int = Field(default=50, ge=1, le=500)


class WorkflowRunStep(ApiModel):
    index: int
    node_id: str
    node_type: str
    status: str
    attempt: int
    duration_ms: int
    input: dict[str, Any] = Field(default_factory=dict)
    output: dict[str, Any] = Field(default_factory=dict)
    error: Optional[str] = None


class WorkflowRunResponse(ApiModel):
    run_id: str
    workflow_id: str
    status: str
    idempotency_key: Optional[str] = None
    idempotent_hit: bool = False
    created_at: Optional[datetime] = None
    started_at: datetime
    completed_at: datetime
    duration_ms: int
    steps: list[WorkflowRunStep]
    final_context: dict[str, Any] = Field(default_factory=dict)
    error: Optional[str] = None


class WorkflowTemplateSummary(ApiModel):
    template_id: str
    name: str
    description: str
    category: str
    node_count: int
    edge_count: int


class WorkflowTemplateListResponse(ApiModel):
    total: int
    items: list[WorkflowTemplateSummary]


class WorkflowTemplateInstantiateRequest(ApiModel):
    name: Optional[str] = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    overrides: dict[str, Any] = Field(default_factory=dict)


class WorkflowTemplateInstantiateResponse(ApiModel):
    template_id: str
    workflow_id: str
    name: str
    created_at: datetime
