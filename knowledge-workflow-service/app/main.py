from fastapi import FastAPI

from app.core.config import settings
from app.models.schemas import HealthResponse
from app.routers.knowledge import router as knowledge_router


app = FastAPI(
    title="jarvis-knowledge-workflow-service",
    version="0.1.0",
    description="B02 MVP: parse, chunk, ingest and lexical search for knowledge content.",
)


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="ok", service=settings.service_name, env=settings.env)


app.include_router(knowledge_router, prefix="/api/v1/knowledge", tags=["knowledge"])
