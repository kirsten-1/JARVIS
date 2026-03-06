from datetime import datetime, timezone
from uuid import uuid4

from fastapi import APIRouter, HTTPException, Query

from app.core.config import settings
from app.models.schemas import (
    WorkflowCreateRequest,
    WorkflowCreateResponse,
    WorkflowDetail,
    WorkflowListResponse,
    WorkflowRunRequest,
    WorkflowRunResponse,
    WorkflowSummary,
)
from app.services.workflow_engine import WorkflowValidationError, execute_workflow, validate_workflow_definition
from app.services.workflow_store import WorkflowStore


router = APIRouter()
store = WorkflowStore(settings.data_dir)


def _to_summary(workflow: dict) -> WorkflowSummary:
    return WorkflowSummary(
        workflow_id=workflow["workflow_id"],
        name=workflow["name"],
        description=workflow.get("description"),
        node_count=len(workflow.get("nodes", [])),
        edge_count=len(workflow.get("edges", [])),
        metadata=workflow.get("metadata", {}),
        created_at=workflow["created_at"],
    )


@router.post("/workflows", response_model=WorkflowCreateResponse)
def create_workflow(request: WorkflowCreateRequest) -> WorkflowCreateResponse:
    nodes = [node.model_dump() for node in request.nodes]
    edges = [edge.model_dump() for edge in request.edges]
    try:
        validate_workflow_definition(nodes=nodes, edges=edges)
    except WorkflowValidationError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    workflow = store.create_workflow(
        name=request.name.strip(),
        description=request.description,
        nodes=nodes,
        edges=edges,
        metadata=request.metadata,
    )
    return WorkflowCreateResponse(
        workflow_id=workflow["workflow_id"],
        name=workflow["name"],
        created_at=workflow["created_at"],
    )


@router.get("/workflows", response_model=WorkflowListResponse)
def list_workflows(
    page: int = Query(default=1, ge=1),
    size: int = Query(default=20, ge=1, le=100),
) -> WorkflowListResponse:
    workflows = store.list_workflows()
    total = len(workflows)
    offset = (page - 1) * size
    items = workflows[offset : offset + size]
    return WorkflowListResponse(total=total, items=[_to_summary(item) for item in items])


@router.get("/workflows/{workflow_id}", response_model=WorkflowDetail)
def get_workflow(workflow_id: str) -> WorkflowDetail:
    workflow = store.get_workflow(workflow_id)
    if workflow is None:
        raise HTTPException(status_code=404, detail="workflow not found")
    return WorkflowDetail.model_validate(workflow)


@router.post("/workflows/{workflow_id}/runs", response_model=WorkflowRunResponse)
def run_workflow(workflow_id: str, request: WorkflowRunRequest) -> WorkflowRunResponse:
    workflow = store.get_workflow(workflow_id)
    if workflow is None:
        raise HTTPException(status_code=404, detail="workflow not found")

    if request.idempotency_key:
        existing = store.find_run_by_idempotency(workflow_id=workflow_id, idempotency_key=request.idempotency_key)
        if existing is not None:
            replay = dict(existing)
            replay["idempotent_hit"] = True
            return WorkflowRunResponse.model_validate(replay)

    run_id = uuid4().hex
    result = execute_workflow(
        workflow=workflow,
        run_id=run_id,
        run_input=request.input,
        max_steps=request.max_steps,
    )
    run_record = {
        "run_id": run_id,
        "workflow_id": workflow_id,
        "status": result["status"],
        "idempotency_key": request.idempotency_key,
        "idempotent_hit": False,
        "started_at": result["started_at"],
        "completed_at": result["completed_at"],
        "duration_ms": result["duration_ms"],
        "steps": result["steps"],
        "final_context": result["final_context"],
        "error": result.get("error"),
        "created_at": datetime.now(timezone.utc).isoformat(),
    }
    store.save_run(run_record)
    return WorkflowRunResponse.model_validate(run_record)


@router.get("/workflow-runs/{run_id}", response_model=WorkflowRunResponse)
def get_workflow_run(run_id: str) -> WorkflowRunResponse:
    run = store.get_run(run_id)
    if run is None:
        raise HTTPException(status_code=404, detail="workflow run not found")
    return WorkflowRunResponse.model_validate(run)
