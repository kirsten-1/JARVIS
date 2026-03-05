from dataclasses import dataclass
from pathlib import Path
import os


@dataclass(frozen=True)
class Settings:
    service_name: str
    env: str
    data_dir: Path
    default_chunk_size: int
    default_chunk_overlap: int
    max_chunk_size: int
    max_chunk_overlap: int


def _int_env(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return int(value)
    except ValueError:
        return default


def load_settings() -> Settings:
    base_dir = Path(__file__).resolve().parents[2]
    data_dir = Path(os.getenv("KNOWLEDGE_DATA_DIR", str(base_dir / "data"))).resolve()

    default_chunk_size = max(100, _int_env("KN_DEFAULT_CHUNK_SIZE", 512))
    default_chunk_overlap = max(0, _int_env("KN_DEFAULT_CHUNK_OVERLAP", 50))
    max_chunk_size = max(default_chunk_size, _int_env("KN_MAX_CHUNK_SIZE", 2000))
    max_chunk_overlap = max(default_chunk_overlap, _int_env("KN_MAX_CHUNK_OVERLAP", 300))

    return Settings(
        service_name=os.getenv("SERVICE_NAME", "jarvis-knowledge-workflow-service"),
        env=os.getenv("APP_ENV", "dev"),
        data_dir=data_dir,
        default_chunk_size=default_chunk_size,
        default_chunk_overlap=default_chunk_overlap,
        max_chunk_size=max_chunk_size,
        max_chunk_overlap=max_chunk_overlap,
    )


settings = load_settings()
