from datetime import datetime, timezone
import json
from pathlib import Path
import threading
import uuid
from typing import Any, Optional


class WorkflowStore:
    def __init__(self, data_dir: Path) -> None:
        self.data_dir = data_dir
        self.data_dir.mkdir(parents=True, exist_ok=True)

        self.workflows_file = self.data_dir / "workflows.jsonl"
        self.runs_file = self.data_dir / "workflow_runs.jsonl"
        self._lock = threading.Lock()

        self.workflows_file.touch(exist_ok=True)
        self.runs_file.touch(exist_ok=True)

    def _read_jsonl(self, path: Path) -> list[dict]:
        items: list[dict] = []
        with path.open("r", encoding="utf-8") as file:
            for line in file:
                line = line.strip()
                if not line:
                    continue
                items.append(json.loads(line))
        return items

    def _append_jsonl(self, path: Path, obj: dict) -> None:
        with path.open("a", encoding="utf-8") as file:
            file.write(json.dumps(obj, ensure_ascii=False) + "\n")

    def create_workflow(
        self,
        name: str,
        description: Optional[str],
        nodes: list[dict],
        edges: list[dict],
        metadata: dict[str, Any],
    ) -> dict:
        now = datetime.now(timezone.utc).isoformat()
        workflow = {
            "workflow_id": uuid.uuid4().hex,
            "name": name,
            "description": description,
            "nodes": nodes,
            "edges": edges,
            "metadata": metadata,
            "created_at": now,
        }
        with self._lock:
            self._append_jsonl(self.workflows_file, workflow)
        return workflow

    def list_workflows(self) -> list[dict]:
        items = self._read_jsonl(self.workflows_file)
        items.sort(key=lambda item: item.get("created_at", ""), reverse=True)
        return items

    def get_workflow(self, workflow_id: str) -> Optional[dict]:
        for workflow in self.list_workflows():
            if workflow.get("workflow_id") == workflow_id:
                return workflow
        return None

    def save_run(self, run: dict) -> None:
        with self._lock:
            self._append_jsonl(self.runs_file, run)

    def get_run(self, run_id: str) -> Optional[dict]:
        runs = self._read_jsonl(self.runs_file)
        for run in reversed(runs):
            if run.get("run_id") == run_id:
                return run
        return None

    def find_run_by_idempotency(self, workflow_id: str, idempotency_key: str) -> Optional[dict]:
        runs = self._read_jsonl(self.runs_file)
        for run in reversed(runs):
            if run.get("workflow_id") == workflow_id and run.get("idempotency_key") == idempotency_key:
                return run
        return None
