from datetime import datetime, timezone
import json
import time
from typing import Any, Optional
import urllib.error
import urllib.request


class WorkflowValidationError(ValueError):
    pass


class WorkflowExecutionError(RuntimeError):
    pass


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _parse_ref_path(raw: str, prefix: str) -> Optional[list[str]]:
    if not isinstance(raw, str):
        return None
    if not raw.startswith(prefix):
        return None
    path = raw[len(prefix) :].strip(".")
    if not path:
        return []
    return [part for part in path.split(".") if part]


def _get_nested(data: Any, path: list[str]) -> Any:
    cur = data
    for key in path:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(key)
    return cur


def _set_nested(data: dict, path: list[str], value: Any) -> None:
    if not path:
        return
    cur = data
    for key in path[:-1]:
        nxt = cur.get(key)
        if not isinstance(nxt, dict):
            nxt = {}
            cur[key] = nxt
        cur = nxt
    cur[path[-1]] = value


def _resolve_value(spec: Any, context: dict) -> Any:
    if isinstance(spec, str):
        input_path = _parse_ref_path(spec, "$input.")
        if input_path is not None:
            return _get_nested(context.get("input", {}), input_path)
        context_path = _parse_ref_path(spec, "$context.")
        if context_path is not None:
            return _get_nested(context, context_path)
    return spec


def _resolve_object(spec: Any, context: dict) -> Any:
    if isinstance(spec, dict):
        return {key: _resolve_object(value, context) for key, value in spec.items()}
    if isinstance(spec, list):
        return [_resolve_object(item, context) for item in spec]
    return _resolve_value(spec, context)


def _render_template(template: str, context: dict) -> str:
    rendered = template
    token_map = {
        "{workflow_id}": str(context.get("workflow_id", "")),
        "{run_id}": str(context.get("run_id", "")),
    }
    for token, value in token_map.items():
        rendered = rendered.replace(token, value)
    return rendered


def _to_bool(value: Any, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    text = str(value).strip().lower()
    if text in {"1", "true", "yes", "approved", "allow"}:
        return True
    if text in {"0", "false", "no", "rejected", "deny"}:
        return False
    return default


def _evaluate_condition(operator: str, left: Any, right: Any) -> bool:
    op = (operator or "equals").strip().lower()
    if op == "equals":
        return left == right
    if op == "not_equals":
        return left != right
    if op == "contains":
        return str(right) in str(left)
    if op == "gt":
        return float(left) > float(right)
    if op == "gte":
        return float(left) >= float(right)
    if op == "lt":
        return float(left) < float(right)
    if op == "lte":
        return float(left) <= float(right)
    raise WorkflowExecutionError(f"unsupported condition operator: {operator}")


def validate_workflow_definition(nodes: list[dict], edges: list[dict]) -> dict:
    if not nodes:
        raise WorkflowValidationError("workflow nodes cannot be empty")
    if not edges:
        raise WorkflowValidationError("workflow edges cannot be empty")

    node_map: dict[str, dict] = {}
    for node in nodes:
        node_id = node.get("node_id")
        if not node_id:
            raise WorkflowValidationError("node_id cannot be empty")
        if node_id in node_map:
            raise WorkflowValidationError(f"duplicated node_id: {node_id}")
        node_map[node_id] = node

    start_nodes = [node for node in nodes if node.get("node_type") == "start"]
    if len(start_nodes) != 1:
        raise WorkflowValidationError("workflow must contain exactly one start node")

    outgoing: dict[str, list[dict]] = {node_id: [] for node_id in node_map.keys()}
    indegree: dict[str, int] = {node_id: 0 for node_id in node_map.keys()}
    for edge in edges:
        from_node = edge.get("from_node")
        to_node = edge.get("to_node")
        if from_node not in node_map:
            raise WorkflowValidationError(f"edge.from_node not found: {from_node}")
        if to_node not in node_map:
            raise WorkflowValidationError(f"edge.to_node not found: {to_node}")
        outgoing[from_node].append(edge)
        indegree[to_node] += 1

    for node_id, node in node_map.items():
        if node.get("node_type") == "end" and outgoing.get(node_id):
            raise WorkflowValidationError(f"end node cannot have outgoing edges: {node_id}")

    start_node_id = start_nodes[0]["node_id"]

    # DFS reachability and cycle check.
    state: dict[str, int] = {}
    reachable: set[str] = set()

    def dfs(node_id: str) -> None:
        current_state = state.get(node_id, 0)
        if current_state == 1:
            raise WorkflowValidationError("workflow graph contains cycle")
        if current_state == 2:
            return
        state[node_id] = 1
        reachable.add(node_id)
        for edge in outgoing.get(node_id, []):
            dfs(edge["to_node"])
        state[node_id] = 2

    dfs(start_node_id)

    unreachable = [node_id for node_id in node_map.keys() if node_id not in reachable]
    if unreachable:
        raise WorkflowValidationError(f"workflow has unreachable nodes: {sorted(unreachable)}")

    return {
        "start_node_id": start_node_id,
        "node_map": node_map,
        "outgoing": outgoing,
        "indegree": indegree,
    }


def _execute_task(config: dict, context: dict) -> dict:
    op = str(config.get("op", "echo")).strip().lower()
    if op == "echo":
        template = str(config.get("template", ""))
        value = _render_template(template, context)
        output_key = str(config.get("output_key", "task.last_message"))
        path = [part for part in output_key.split(".") if part]
        _set_nested(context, path, value)
        return {"op": op, "output_key": output_key, "value": value}

    if op == "set":
        output_key = str(config.get("output_key", "task.value"))
        value = _resolve_value(config.get("value"), context)
        path = [part for part in output_key.split(".") if part]
        _set_nested(context, path, value)
        return {"op": op, "output_key": output_key, "value": value}

    if op == "add":
        output_key = str(config.get("output_key", "task.counter"))
        path = [part for part in output_key.split(".") if part]
        current = _get_nested(context, path) or 0
        increment = _resolve_value(config.get("increment", 1), context)
        value = float(current) + float(increment)
        if float(value).is_integer():
            value = int(value)
        _set_nested(context, path, value)
        return {"op": op, "output_key": output_key, "value": value}

    if op == "sleep":
        milliseconds = int(config.get("milliseconds", 0))
        if milliseconds > 0:
            time.sleep(milliseconds / 1000.0)
        return {"op": op, "slept_ms": milliseconds}

    if op == "fail":
        message = str(config.get("message", "task forced failure"))
        raise WorkflowExecutionError(message)

    raise WorkflowExecutionError(f"unsupported task op: {op}")


def _execute_approval(config: dict, context: dict) -> tuple[dict, str]:
    decision_ref = str(config.get("decision_ref", "$input.review.approved"))
    reviewer_ref = str(config.get("reviewer_ref", "$input.review.reviewer"))
    comment_ref = str(config.get("comment_ref", "$input.review.comment"))
    require_explicit = _to_bool(config.get("require_explicit", True), default=True)

    raw_decision = _resolve_value(decision_ref, context)
    reviewer = _resolve_value(reviewer_ref, context)
    comment = _resolve_value(comment_ref, context)

    if raw_decision is None and require_explicit:
        raise WorkflowExecutionError("approval decision missing")

    approved = _to_bool(raw_decision, default=False)
    route = "approved" if approved else "rejected"
    output = {
        "approved": approved,
        "reviewer": reviewer,
        "comment": comment,
        "decision_ref": decision_ref,
        "route": route,
    }

    output_key = str(config.get("output_key", "approval.last"))
    path = [part for part in output_key.split(".") if part]
    _set_nested(context, path, output)
    return output, route


def _execute_webhook(config: dict, context: dict) -> dict:
    method = str(config.get("method", "POST")).strip().upper()
    url = str(_resolve_value(config.get("url"), context) or "").strip()
    if not url:
        raise WorkflowExecutionError("webhook url is empty")

    headers = _resolve_object(config.get("headers", {}), context)
    payload_obj = _resolve_object(config.get("payload", {}), context)
    dry_run = _to_bool(config.get("dry_run", False), default=False)
    request_timeout_ms = int(config.get("request_timeout_ms", 3000))

    if not isinstance(headers, dict):
        raise WorkflowExecutionError("webhook headers must be an object")

    if dry_run:
        output = {
            "dry_run": True,
            "method": method,
            "url": url,
            "headers": headers,
            "payload": payload_obj,
        }
    else:
        data = json.dumps(payload_obj, ensure_ascii=False).encode("utf-8")
        req_headers = {"Content-Type": "application/json"}
        for key, value in headers.items():
            req_headers[str(key)] = str(value)
        req = urllib.request.Request(url=url, data=data, method=method, headers=req_headers)
        try:
            with urllib.request.urlopen(req, timeout=max(request_timeout_ms, 1) / 1000.0) as resp:
                raw = resp.read().decode("utf-8")
                output = {
                    "dry_run": False,
                    "method": method,
                    "url": url,
                    "status_code": int(resp.getcode() or 0),
                    "response_body": raw[:2000],
                }
        except urllib.error.URLError as exc:
            raise WorkflowExecutionError(f"webhook request failed: {exc}") from exc

    output_key = str(config.get("output_key", "webhook.last"))
    path = [part for part in output_key.split(".") if part]
    _set_nested(context, path, output)
    return output


def _select_next_node(current_node: dict, outgoing_edges: list[dict], route: Optional[str]) -> Optional[str]:
    if not outgoing_edges:
        return None
    if current_node.get("node_type") in {"condition", "approval"}:
        normalized_route = (route or "").strip().lower()
        for edge in outgoing_edges:
            edge_condition = str(edge.get("condition") or "").strip().lower()
            if edge_condition and edge_condition == normalized_route:
                return edge["to_node"]
        for edge in outgoing_edges:
            if not edge.get("condition"):
                return edge["to_node"]
    return outgoing_edges[0]["to_node"]


def execute_workflow(workflow: dict, run_id: str, run_input: dict, max_steps: int) -> dict:
    validation = validate_workflow_definition(workflow.get("nodes", []), workflow.get("edges", []))
    start_node_id = validation["start_node_id"]
    node_map = validation["node_map"]
    outgoing = validation["outgoing"]

    started_at = _now_iso()
    begin = time.perf_counter()
    context: dict[str, Any] = {
        "workflow_id": workflow["workflow_id"],
        "run_id": run_id,
        "input": run_input or {},
    }
    steps: list[dict] = []
    current_node_id: Optional[str] = start_node_id
    status = "success"
    error: Optional[str] = None

    for index in range(1, max_steps + 1):
        if current_node_id is None:
            break
        node = node_map[current_node_id]
        node_type = node["node_type"]
        config = node.get("config", {}) or {}
        retry_max = max(int(config.get("retry_max", 0)), 0)
        timeout_ms = max(int(config.get("timeout_ms", 0)), 0)

        route: Optional[str] = None
        success = False
        attempt = 0
        last_error: Optional[str] = None
        output: dict[str, Any] = {}
        duration_ms = 0

        for attempt in range(1, retry_max + 2):
            step_start = time.perf_counter()
            try:
                if node_type == "start":
                    output = {"message": "start node entered"}
                elif node_type == "task":
                    output = _execute_task(config=config, context=context)
                elif node_type == "condition":
                    left = _resolve_value(config.get("left"), context)
                    right = _resolve_value(config.get("right"), context)
                    operator = str(config.get("operator", "equals"))
                    passed = _evaluate_condition(operator=operator, left=left, right=right)
                    route = "true" if passed else "false"
                    output = {"operator": operator, "left": left, "right": right, "passed": passed}
                elif node_type == "approval":
                    output, route = _execute_approval(config=config, context=context)
                elif node_type == "webhook":
                    output = _execute_webhook(config=config, context=context)
                elif node_type == "end":
                    output = {"message": "end node reached"}
                else:
                    raise WorkflowExecutionError(f"unsupported node_type: {node_type}")

                duration_ms = int((time.perf_counter() - step_start) * 1000)
                if timeout_ms > 0 and duration_ms > timeout_ms:
                    raise WorkflowExecutionError(
                        f"node timeout: {node['node_id']} cost={duration_ms}ms exceeds {timeout_ms}ms"
                    )
                success = True
                break
            except Exception as exc:  # noqa: BLE001
                duration_ms = int((time.perf_counter() - step_start) * 1000)
                last_error = str(exc)
                if attempt > retry_max:
                    break

        step = {
            "index": index,
            "node_id": node["node_id"],
            "node_type": node_type,
            "status": "success" if success else "failed",
            "attempt": attempt,
            "duration_ms": duration_ms,
            "input": {"context_snapshot": context.get("input", {})},
            "output": output if success else {},
            "error": None if success else last_error,
        }
        steps.append(step)

        if not success:
            status = "failed"
            error = last_error or "workflow node execution failed"
            break

        next_node_id = _select_next_node(node, outgoing.get(node["node_id"], []), route)
        current_node_id = next_node_id

    if current_node_id is not None and status == "success" and len(steps) >= max_steps:
        status = "failed"
        error = f"max_steps exceeded: {max_steps}"

    completed_at = _now_iso()
    duration_total_ms = int((time.perf_counter() - begin) * 1000)
    return {
        "status": status,
        "started_at": started_at,
        "completed_at": completed_at,
        "duration_ms": duration_total_ms,
        "steps": steps,
        "final_context": context,
        "error": error,
    }
