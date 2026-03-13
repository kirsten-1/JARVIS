from fastapi import FastAPI

from app.core.config import settings
from app.models.schemas import HealthResponse
from app.routers.knowledge import router as knowledge_router
from app.routers.workflow import router as workflow_router


app = FastAPI(
    title="jarvis-knowledge-workflow-service",
    version="0.3.0",
    description="B06 MVP: knowledge retrieval + workflow DAG with approval/webhook/template flows.",
)


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="ok", service=settings.service_name, env=settings.env)


app.include_router(knowledge_router, prefix="/api/v1/knowledge", tags=["knowledge"])
app.include_router(workflow_router, prefix="/api/v1", tags=["workflow"])
