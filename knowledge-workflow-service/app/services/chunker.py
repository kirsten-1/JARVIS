def chunk_text(text: str, chunk_size: int, chunk_overlap: int) -> list[str]:
    if chunk_size <= 0:
        raise ValueError("chunk_size must be > 0")
    if chunk_overlap < 0:
        raise ValueError("chunk_overlap must be >= 0")
    if chunk_overlap >= chunk_size:
        raise ValueError("chunk_overlap must be smaller than chunk_size")

    normalized = text.strip()
    if not normalized:
        return []

    chunks: list[str] = []
    start = 0
    step = chunk_size - chunk_overlap

    while start < len(normalized):
        end = min(len(normalized), start + chunk_size)
        part = normalized[start:end]

        # Prefer cutting on natural boundaries if available.
        if end < len(normalized):
            boundary = max(part.rfind("\n"), part.rfind("。"), part.rfind("."), part.rfind("!"), part.rfind("?"))
            if boundary > int(chunk_size * 0.5):
                end = start + boundary + 1
                part = normalized[start:end]

        chunks.append(part.strip())
        start = max(start + step, end - chunk_overlap)
        if start >= len(normalized):
            break

    # Remove accidental empties.
    return [chunk for chunk in chunks if chunk]
