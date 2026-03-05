from datetime import datetime, timezone
import json
from pathlib import Path
import threading
import uuid


class KnowledgeStore:
    def __init__(self, data_dir: Path) -> None:
        self.data_dir = data_dir
        self.data_dir.mkdir(parents=True, exist_ok=True)

        self.documents_file = self.data_dir / "documents.jsonl"
        self.chunks_file = self.data_dir / "chunks.jsonl"
        self._lock = threading.Lock()

        self.documents_file.touch(exist_ok=True)
        self.chunks_file.touch(exist_ok=True)

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

    def create_document(self, title: str, source_type: str, metadata: dict, chunk_count: int) -> str:
        document_id = uuid.uuid4().hex
        now = datetime.now(timezone.utc).isoformat()

        document = {
            "document_id": document_id,
            "title": title,
            "source_type": source_type,
            "metadata": metadata,
            "chunk_count": chunk_count,
            "created_at": now,
        }

        with self._lock:
            self._append_jsonl(self.documents_file, document)

        return document_id

    def add_chunks(self, document_id: str, chunks: list[str]) -> None:
        with self._lock:
            for index, content in enumerate(chunks):
                self._append_jsonl(
                    self.chunks_file,
                    {
                        "chunk_id": f"{document_id}-{index}",
                        "document_id": document_id,
                        "chunk_index": index,
                        "content": content,
                    },
                )

    def list_documents(self) -> list[dict]:
        docs = self._read_jsonl(self.documents_file)
        docs.sort(key=lambda item: item.get("created_at", ""), reverse=True)
        return docs

    def list_chunks(self, document_id: str | None = None) -> list[dict]:
        chunks = self._read_jsonl(self.chunks_file)
        if document_id is not None:
            chunks = [chunk for chunk in chunks if chunk.get("document_id") == document_id]
        return chunks
