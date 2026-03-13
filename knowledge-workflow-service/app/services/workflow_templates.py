import copy
from typing import Any, Optional


_TEMPLATES: dict[str, dict[str, Any]] = {
    "content-approval-webhook": {
        "template_id": "content-approval-webhook",
        "name": "Content Approval With Webhook",
        "description": "Draft content, require review decision, then publish and callback.",
        "category": "approval",
        "workflow": {
            "description": "B06 content approval template",
            "nodes": [
                {"node_id": "start", "node_type": "start", "config": {}},
                {
                    "node_id": "draft",
                    "node_type": "task",
                    "config": {
                        "op": "echo",
                        "template": "draft prepared by workflow {workflow_id}",
                        "output_key": "draft.message",
                    },
                },
                {
                    "node_id": "approval",
                    "node_type": "approval",
                    "config": {
                        "decision_ref": "$input.review.approved",
                        "reviewer_ref": "$input.review.reviewer",
                        "comment_ref": "$input.review.comment",
                        "require_explicit": True,
                        "output_key": "review.last",
                    },
                },
                {
                    "node_id": "publish",
                    "node_type": "task",
                    "config": {"op": "set", "output_key": "result.status", "value": "published"},
                },
                {
                    "node_id": "manual",
                    "node_type": "task",
                    "config": {"op": "set", "output_key": "result.status", "value": "pending_review"},
                },
                {
                    "node_id": "notify",
                    "node_type": "webhook",
                    "config": {
                        "method": "POST",
                        "url": "https://example.invalid/jarvis/workflow-callback",
                        "dry_run": True,
                        "headers": {"X-Source": "jarvis-b06"},
                        "payload": {
                            "workflow_id": "$context.workflow_id",
                            "run_id": "$context.run_id",
                            "status": "$context.result.status",
                            "reviewer": "$context.review.last.reviewer",
                        },
                        "output_key": "webhook.last",
                    },
                },
                {"node_id": "end", "node_type": "end", "config": {}},
            ],
            "edges": [
                {"from_node": "start", "to_node": "draft"},
                {"from_node": "draft", "to_node": "approval"},
                {"from_node": "approval", "to_node": "publish", "condition": "approved"},
                {"from_node": "approval", "to_node": "manual", "condition": "rejected"},
                {"from_node": "publish", "to_node": "notify"},
                {"from_node": "manual", "to_node": "end"},
                {"from_node": "notify", "to_node": "end"},
            ],
        },
    },
    "incident-escalation-webhook": {
        "template_id": "incident-escalation-webhook",
        "name": "Incident Escalation",
        "description": "Escalate incident by severity and trigger callback.",
        "category": "ops",
        "workflow": {
            "description": "B06 incident escalation template",
            "nodes": [
                {"node_id": "start", "node_type": "start", "config": {}},
                {
                    "node_id": "judge",
                    "node_type": "condition",
                    "config": {"left": "$input.incident.severity", "operator": "gte", "right": 3},
                },
                {
                    "node_id": "escalate",
                    "node_type": "task",
                    "config": {"op": "set", "output_key": "result.status", "value": "escalated"},
                },
                {
                    "node_id": "normal",
                    "node_type": "task",
                    "config": {"op": "set", "output_key": "result.status", "value": "normal"},
                },
                {
                    "node_id": "callback",
                    "node_type": "webhook",
                    "config": {
                        "method": "POST",
                        "url": "https://example.invalid/jarvis/incident-callback",
                        "dry_run": True,
                        "payload": {
                            "incident_id": "$input.incident.id",
                            "severity": "$input.incident.severity",
                            "result": "$context.result.status",
                        },
                        "output_key": "webhook.last",
                    },
                },
                {"node_id": "end", "node_type": "end", "config": {}},
            ],
            "edges": [
                {"from_node": "start", "to_node": "judge"},
                {"from_node": "judge", "to_node": "escalate", "condition": "true"},
                {"from_node": "judge", "to_node": "normal", "condition": "false"},
                {"from_node": "escalate", "to_node": "callback"},
                {"from_node": "normal", "to_node": "callback"},
                {"from_node": "callback", "to_node": "end"},
            ],
        },
    },
}


def list_templates() -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    for template in _TEMPLATES.values():
        workflow = template["workflow"]
        items.append(
            {
                "template_id": template["template_id"],
                "name": template["name"],
                "description": template["description"],
                "category": template["category"],
                "node_count": len(workflow["nodes"]),
                "edge_count": len(workflow["edges"]),
            }
        )
    items.sort(key=lambda item: item["template_id"])
    return items


def get_template(template_id: str) -> Optional[dict[str, Any]]:
    template = _TEMPLATES.get(template_id)
    if template is None:
        return None
    return copy.deepcopy(template)


def instantiate_template(
    template_id: str,
    name: Optional[str] = None,
    metadata: Optional[dict[str, Any]] = None,
    overrides: Optional[dict[str, Any]] = None,
) -> Optional[dict[str, Any]]:
    template = get_template(template_id)
    if template is None:
        return None

    workflow = template["workflow"]
    nodes = workflow["nodes"]
    edges = workflow["edges"]

    overrides = overrides or {}
    webhook_url = overrides.get("webhook_url")
    webhook_dry_run = overrides.get("webhook_dry_run")
    approval_required = overrides.get("approval_required")

    for node in nodes:
        if node.get("node_type") == "webhook":
            if webhook_url:
                node["config"]["url"] = webhook_url
            if webhook_dry_run is not None:
                node["config"]["dry_run"] = bool(webhook_dry_run)
        if node.get("node_type") == "approval" and approval_required is not None:
            node["config"]["require_explicit"] = bool(approval_required)

    final_metadata = {"template_id": template_id}
    if metadata:
        final_metadata.update(metadata)

    return {
        "template_id": template_id,
        "name": name or template["name"],
        "description": workflow.get("description"),
        "nodes": nodes,
        "edges": edges,
        "metadata": final_metadata,
    }
